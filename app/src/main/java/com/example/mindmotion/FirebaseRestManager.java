package com.example.mindmotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FirebaseRestManager {
    private static final String TAG = "FirebaseRestManager";

    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String MOTION_SESSIONS_COLLECTION = "motion_sessions";
    private static final String VOICE_DATA_COLLECTION = "voice_data";

    // Firebase Auth REST API
    private static final String FIREBASE_API_KEY = "AIzaSyC7bPi7suzy8DmMFSgP7n090t7zHXzI5Bk";
    private static final String AUTH_BASE_URL = "https://identitytoolkit.googleapis.com/v1/accounts";

    private ExecutorService executor;
    private Handler mainHandler;
    private SessionPollerListener listener;
    private boolean isPolling = false;
    private Set<String> processedSessions = new HashSet<>();

    // User authentication
    private String currentUserId;
    private String idToken;
    private String refreshToken;
    private long tokenExpirationTime;
    private Context context;

    // Token refresh state - improved synchronization
    private volatile boolean isRefreshingToken = false;
    private final Object refreshLock = new Object();
    private CountDownLatch refreshLatch = null;

    private int consecutiveAuthFailures = 0;
    private static final int MAX_CONSECUTIVE_AUTH_FAILURES = 3;

    public interface SessionPollerListener {
        void onNewSessionFound(String sessionId, String motionType, String studentId);
        void onSessionTimedOut(String sessionId);
        void onError(String error);
        void onMotionMarked(String sessionId);
        void onVoiceDataSaved(String date, String data);
    }

    public FirebaseRestManager(Context context) {
        this.context = context;
        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
        loadTokensFromPreferences();
    }

    private void loadTokensFromPreferences() {
        SharedPreferences prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        currentUserId = prefs.getString("USER_ID", "");
        idToken = prefs.getString("ID_TOKEN", "");
        refreshToken = prefs.getString("REFRESH_TOKEN", "");
        tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        Log.d(TAG, "FirebaseRestManager initialized for user: " + currentUserId);
        Log.d(TAG, "Token expiration time: " + tokenExpirationTime + " (current: " + System.currentTimeMillis() + ")");
    }

    public void setListener(SessionPollerListener listener) {
        this.listener = listener;
    }

    public void startPollingForSessions() {
        if (isPolling) return;

        if (currentUserId.isEmpty()) {
            Log.e(TAG, "No user ID found. Cannot start polling.");
            notifyError("User not authenticated");
            return;
        }

        if (refreshToken.isEmpty()) {
            Log.e(TAG, "No refresh token found. Cannot start polling.");
            notifyError("Authentication token missing - please login again");
            return;
        }

        // Reset failure counter when starting fresh
        consecutiveAuthFailures = 0;

        isPolling = true;
        Log.d(TAG, "Starting to poll for motion sessions for user: " + currentUserId);

        // Start polling
        pollForSessions();
    }

    public void stopPolling() {
        isPolling = false;
        Log.d(TAG, "Stopping session polling");
    }

    private boolean isTokenExpired() {
        // Consider token expired if it expires within the next 5 minutes
        long fiveMinutesFromNow = System.currentTimeMillis() + (5 * 60 * 1000);
        boolean expired = tokenExpirationTime <= fiveMinutesFromNow;
        if (expired) {
            Log.d(TAG, "Token is expired. Expiry: " + tokenExpirationTime + ", Current + 5min: " + fiveMinutesFromNow);
        }
        return expired;
    }

    /**
     * Ensures we have a valid token. If token is expired, refreshes it.
     * This method handles synchronization to prevent multiple refresh attempts.
     */
    private boolean ensureValidToken() {
        if (!isTokenExpired()) {
            return true;
        }

        synchronized (refreshLock) {
            // Double-check after acquiring lock
            if (!isTokenExpired()) {
                return true;
            }

            if (isRefreshingToken) {
                // Another thread is already refreshing, wait for it
                try {
                    if (refreshLatch != null) {
                        Log.d(TAG, "Waiting for ongoing token refresh...");
                        boolean completed = refreshLatch.await(15, TimeUnit.SECONDS);
                        if (!completed) {
                            Log.e(TAG, "Token refresh timeout");
                            return false;
                        }
                        return !isTokenExpired();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // We need to refresh
            isRefreshingToken = true;
            refreshLatch = new CountDownLatch(1);

            try {
                boolean success = refreshIdTokenSync();
                return success;
            } finally {
                isRefreshingToken = false;
                if (refreshLatch != null) {
                    refreshLatch.countDown();
                }
            }
        }
    }

    private boolean refreshIdTokenSync() {
        if (refreshToken.isEmpty()) {
            Log.e(TAG, "No refresh token available");
            consecutiveAuthFailures++;
            return false;
        }

        try {
            Log.d(TAG, "Refreshing ID token...");
            String refreshUrl = AUTH_BASE_URL + ":token?key=" + FIREBASE_API_KEY;

            JSONObject payload = new JSONObject();
            payload.put("grant_type", "refresh_token");
            payload.put("refresh_token", refreshToken);

            HttpURLConnection connection = (HttpURLConnection) new URL(refreshUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);

            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream())) {
                writer.write(payload.toString());
                writer.flush();
            }

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Token refresh response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject refreshResponse = new JSONObject(response.toString());
                String newIdToken = refreshResponse.getString("id_token");
                String newRefreshToken = refreshResponse.optString("refresh_token", refreshToken);

                // Get expires_in field if available, default to 3600 seconds (1 hour)
                int expiresIn = refreshResponse.optInt("expires_in", 3600);

                // Calculate expiration time based on expires_in
                // Use 90% of the actual expiry time to ensure we refresh before it actually expires
                long bufferTime = (long)(expiresIn * 0.9 * 1000);
                long newExpirationTime = System.currentTimeMillis() + bufferTime;

                // Update tokens
                idToken = newIdToken;
                refreshToken = newRefreshToken;
                tokenExpirationTime = newExpirationTime;

                // Save to SharedPreferences
                saveTokensToPreferences();

                Log.d(TAG, "ID token refreshed successfully");
                Log.d(TAG, "Token will expire in " + (bufferTime/1000) + " seconds");
                Log.d(TAG, "Expiration time: " + new java.util.Date(newExpirationTime));

                // Reset failure counter on success
                consecutiveAuthFailures = 0;

                connection.disconnect();
                return true;

            } else {
                // Read error response
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                errorReader.close();

                Log.e(TAG, "Failed to refresh token, response code: " + responseCode + ", error: " + errorResponse.toString());

                consecutiveAuthFailures++;

                // If refresh token is invalid, user needs to login again
                if (responseCode == 400) {
                    JSONObject errorJson = new JSONObject(errorResponse.toString());
                    if (errorJson.has("error")) {
                        JSONObject error = errorJson.getJSONObject("error");
                        String errorMessage = error.optString("message", "");

                        if (errorMessage.contains("INVALID_REFRESH_TOKEN") ||
                                errorMessage.contains("TOKEN_EXPIRED")) {
                            Log.e(TAG, "Refresh token is invalid or expired");
                            clearAuthData();
                            // Don't retry with invalid refresh token
                            consecutiveAuthFailures = MAX_CONSECUTIVE_AUTH_FAILURES;
                        }
                    }
                }

                connection.disconnect();
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error refreshing token", e);
            consecutiveAuthFailures++;
            return false;
        }
    }

    private void saveTokensToPreferences() {
        SharedPreferences prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("ID_TOKEN", idToken)
                .putString("REFRESH_TOKEN", refreshToken)
                .putLong("TOKEN_EXPIRATION_TIME", tokenExpirationTime)
                .apply();
    }

    private void clearAuthData() {
        SharedPreferences prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        prefs.edit()
                .remove("ID_TOKEN")
                .remove("REFRESH_TOKEN")
                .remove("TOKEN_EXPIRATION_TIME")
                .apply();

        idToken = "";
        refreshToken = "";
        tokenExpirationTime = 0;
    }

    private void pollForSessions() {
        if (!isPolling) return;

        executor.execute(() -> {
            try {
                // Check if we've had too many consecutive auth failures
                if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                    Log.e(TAG, "Too many consecutive auth failures, stopping polling");
                    notifyError("Authentication failed repeatedly - please login again");
                    stopPolling();
                    return;
                }

                // Ensure we have a valid token before making the request
                if (!ensureValidToken()) {
                    if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                        notifyError("Authentication expired - please login again");
                        stopPolling();
                    } else {
                        // Try again in a bit
                        Log.d(TAG, "Token refresh failed, will retry polling in 5 seconds");
                        if (isPolling) {
                            mainHandler.postDelayed(this::pollForSessions, 5000);
                        }
                    }
                    return;
                }

                String queryUrl = BASE_URL + "/" + MOTION_SESSIONS_COLLECTION
                        + "?pageSize=50"
                        + "&orderBy=timestamp%20desc";

                URL url = new URL(queryUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + idToken);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Poll response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String responseBody = response.toString();
                    parseSimpleResponse(responseBody);

                    // Reset failure counter on successful request
                    consecutiveAuthFailures = 0;

                } else if (responseCode == 401 || responseCode == 403) {
                    Log.w(TAG, "Received " + responseCode + " error during polling");

                    // Force token refresh and retry once
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0; // Force refresh
                    }

                    if (ensureValidToken()) {
                        Log.d(TAG, "Token refreshed, retrying poll request immediately");
                        if (isPolling) {
                            // Retry immediately with new token (not recursive, just one retry)
                            pollForSessions();
                        }
                        return;
                    } else {
                        // Don't immediately say "please login again" - might be temporary
                        if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                            notifyError("Authentication failed - please login again");
                            stopPolling();
                        }
                        return;
                    }
                } else {
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

                    Log.e(TAG, "HTTP Error " + responseCode + ": " + errorResponse.toString());
                    // Don't notify error for every failed request
                    if (responseCode >= 500) {
                        Log.d(TAG, "Server error, will retry");
                    } else {
                        notifyError("Query failed: " + responseCode);
                    }
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error polling sessions", e);
                // Don't notify for network errors, just retry
                if (e instanceof java.net.SocketTimeoutException ||
                        e instanceof java.net.UnknownHostException) {
                    Log.d(TAG, "Network error, will retry");
                } else {
                    notifyError("Polling error: " + e.getMessage());
                }
            }

            // Continue polling if still active
            if (isPolling) {
                mainHandler.postDelayed(this::pollForSessions, 3000);
            }
        });
    }

    // Rest of your existing methods remain the same, but update them to use ensureValidToken()
    public void markMotionDetected(String sessionId) {
        executor.execute(() -> {
            try {
                if (!ensureValidToken()) {
                    notifyError("Authentication expired - please login again");
                    return;
                }

                Log.d(TAG, "Marking motion detected for session: " + sessionId);

                JSONObject payload = new JSONObject();
                JSONObject fields = new JSONObject();

                JSONObject detectedField = new JSONObject();
                detectedField.put("booleanValue", true);
                fields.put("detected", detectedField);

                JSONObject statusField = new JSONObject();
                statusField.put("stringValue", "completed");
                fields.put("status", statusField);

                JSONObject completedAtField = new JSONObject();
                completedAtField.put("integerValue", String.valueOf(System.currentTimeMillis()));
                fields.put("completedAt", completedAtField);

                payload.put("fields", fields);

                String urlString = BASE_URL + "/" + MOTION_SESSIONS_COLLECTION + "/" + sessionId;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PATCH");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + idToken);
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
                writer.write(payload.toString());
                writer.flush();
                writer.close();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Successfully marked motion detected for session: " + sessionId);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onMotionMarked(sessionId);
                        }
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    Log.w(TAG, "Auth error when marking motion, retrying with fresh token");
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0; // Force refresh
                    }
                    if (ensureValidToken()) {
                        markMotionDetected(sessionId); // Retry
                    } else {
                        notifyError("Authentication failed - please login again");
                    }
                } else {
                    Log.e(TAG, "Failed to update session " + responseCode);
                    notifyError("Failed to update session: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error marking motion detected", e);
                notifyError("Update error: " + e.getMessage());
            }
        });
    }

    // Copy the rest of your existing methods here, making sure to replace
    // the token refresh logic with calls to ensureValidToken()

    private void parseSimpleResponse(String jsonResponse) {
        try {
            JSONObject response = new JSONObject(jsonResponse);

            if (!response.has("documents")) {
                Log.d(TAG, "No documents found in response");
                return;
            }

            JSONArray documents = response.getJSONArray("documents");
            Log.d(TAG, "Found " + documents.length() + " documents");

            for (int i = 0; i < documents.length(); i++) {
                JSONObject doc = documents.getJSONObject(i);
                String documentPath = doc.getString("name");
                String sessionId = extractSessionIdFromPath(documentPath);

                if (processedSessions.contains(sessionId)) {
                    continue;
                }

                JSONObject fields = doc.getJSONObject("fields");

                // Extract session data
                String sessionUserId = null;
                String motionType = null;
                String status = null;
                long timestamp = 0;

                if (fields.has("studentId") && fields.getJSONObject("studentId").has("stringValue")) {
                    sessionUserId = fields.getJSONObject("studentId").getString("stringValue");
                }

                if (fields.has("motionType") && fields.getJSONObject("motionType").has("stringValue")) {
                    motionType = fields.getJSONObject("motionType").getString("stringValue");
                }

                if (fields.has("status") && fields.getJSONObject("status").has("stringValue")) {
                    status = fields.getJSONObject("status").getString("stringValue");
                }

                if (fields.has("timestamp") && fields.getJSONObject("timestamp").has("integerValue")) {
                    String timestampStr = fields.getJSONObject("timestamp").getString("integerValue");
                    timestamp = Long.parseLong(timestampStr);
                }

                Log.d(TAG, "Processing session: " + sessionId +
                        ", userId: " + sessionUserId +
                        ", status: " + status +
                        ", motionType: " + motionType);

                // Verify user and status
                if (!currentUserId.equals(sessionUserId)) {
                    Log.d(TAG, "Skipping session " + sessionId + " - user mismatch");
                    continue;
                }

                if (!"waiting".equals(status)) {
                    Log.d(TAG, "Skipping session " + sessionId + " - status: " + status);
                    continue;
                }

                // Check if session is expired
                long timestampMs = timestamp * 1000;
                if (timestamp > 0 && isSessionExpired(timestampMs)) {
                    Log.d(TAG, "Session expired: " + sessionId);
                    markSessionAsTimedOut(sessionId);
                    continue;
                }

                // Process new session
                processedSessions.add(sessionId);

                if (motionType != null) {
                    final String finalSessionId = sessionId;
                    final String finalMotionType = motionType;
                    final String finalStudentId = sessionUserId;

                    Log.d(TAG, "Found new session: " + finalSessionId + " motion: " + finalMotionType);

                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onNewSessionFound(finalSessionId, finalMotionType, finalStudentId);
                        }
                    });
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing simple response", e);
            notifyError("Response parsing error: " + e.getMessage());
        }
    }

    private String extractSessionIdFromPath(String documentPath) {
        String[] parts = documentPath.split("/");
        return parts[parts.length - 1];
    }

    public void markSessionAsTimedOut(String sessionId) {
        executor.execute(() -> {
            try {
                if (!ensureValidToken()) {
                    Log.e(TAG, "Cannot timeout session - authentication expired");
                    return;
                }

                Log.d(TAG, "Marking session as timed out: " + sessionId);

                JSONObject payload = new JSONObject();
                JSONObject fields = new JSONObject();

                JSONObject statusField = new JSONObject();
                statusField.put("stringValue", "timeout");
                fields.put("status", statusField);

                JSONObject timedOutAtField = new JSONObject();
                timedOutAtField.put("integerValue", String.valueOf(System.currentTimeMillis()));
                fields.put("timedOutAt", timedOutAtField);

                payload.put("fields", fields);

                String urlString = BASE_URL + "/" + MOTION_SESSIONS_COLLECTION + "/" + sessionId;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PATCH");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + idToken);
                connection.setDoOutput(true);

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
                writer.write(payload.toString());
                writer.flush();
                writer.close();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Successfully marked session as timed out: " + sessionId);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onSessionTimedOut(sessionId);
                        }
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    Log.w(TAG, "Auth error when timing out session, retrying with fresh token");
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0; // Force refresh
                    }
                    if (ensureValidToken()) {
                        markSessionAsTimedOut(sessionId); // Retry
                    }
                } else {
                    Log.e(TAG, "Failed to timeout session. Response code: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error marking session as timed out", e);
            }
        });
    }

    public void saveVoiceData(String spokenText) {
        if (currentUserId.isEmpty()) {
            Log.e(TAG, "Cannot save voice data: No user authenticated");
            return;
        }

        executor.execute(() -> {
            try {
                if (!ensureValidToken()) {
                    notifyError("Authentication expired - please login again");
                    return;
                }

                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                String documentId = currentUserId + "_" + today;

                Log.d(TAG, "Saving voice data for user: " + currentUserId + " on date: " + today);

                String getUrlString = BASE_URL + "/" + VOICE_DATA_COLLECTION + "/" + documentId;
                JSONObject existingData = getExistingVoiceData(getUrlString);
                JSONArray wordsArray = new JSONArray();

                if (existingData != null && existingData.has("fields")
                        && existingData.getJSONObject("fields").has("words")
                        && existingData.getJSONObject("fields").getJSONObject("words").has("arrayValue")
                        && existingData.getJSONObject("fields").getJSONObject("words").getJSONObject("arrayValue").has("values")) {

                    wordsArray = existingData.getJSONObject("fields")
                            .getJSONObject("words")
                            .getJSONObject("arrayValue")
                            .getJSONArray("values");
                }

                JSONObject newWord = new JSONObject();
                JSONObject newWordValue = new JSONObject();
                newWordValue.put("stringValue", spokenText);
                newWord.put("value", newWordValue);
                newWord.put("timestamp", new JSONObject().put("integerValue", System.currentTimeMillis()));
                wordsArray.put(newWord);

                JSONObject payload = new JSONObject();
                JSONObject fields = new JSONObject();

                JSONObject userIdField = new JSONObject();
                userIdField.put("stringValue", currentUserId);
                fields.put("userId", userIdField);

                JSONObject dateField = new JSONObject();
                dateField.put("stringValue", today);
                fields.put("date", dateField);

                JSONObject wordsField = new JSONObject();
                JSONObject arrayValue = new JSONObject();
                arrayValue.put("values", wordsArray);
                wordsField.put("arrayValue", arrayValue);
                fields.put("words", wordsField);

                JSONObject lastUpdatedField = new JSONObject();
                lastUpdatedField.put("integerValue", System.currentTimeMillis());
                fields.put("lastUpdated", lastUpdatedField);

                payload.put("fields", fields);

                URL url = new URL(getUrlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PATCH");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + idToken);
                connection.setDoOutput(true);

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
                writer.write(payload.toString());
                writer.flush();
                writer.close();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Voice data saved successfully for " + today);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onVoiceDataSaved(today, spokenText);
                        }
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    Log.w(TAG, "Auth error when saving voice data, retrying with fresh token");
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0; // Force refresh
                    }
                    if (ensureValidToken()) {
                        saveVoiceData(spokenText); // Retry
                    } else {
                        notifyError("Authentication failed - please login again");
                    }
                } else {
                    Log.e(TAG, "Failed to save voice data. Response code: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error saving voice data", e);
                notifyError("Voice data save error: " + e.getMessage());
            }
        });
    }

    private JSONObject getExistingVoiceData(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + idToken);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();
                return new JSONObject(response.toString());
            }
            connection.disconnect();
        } catch (Exception e) {
            Log.d(TAG, "No existing voice data found or error reading: " + e.getMessage());
        }
        return null;
    }

    private boolean isSessionExpired(long timestamp) {
        long currentTime = System.currentTimeMillis();
        long sessionAge = currentTime - timestamp;
        return sessionAge > 70000; // 70 seconds
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(error);
            }
        });
    }

    public void forceTokenRefresh() {
        executor.execute(() -> {
            synchronized (refreshLock) {
                tokenExpirationTime = 0; // Force expiration
                boolean success = ensureValidToken();
                if (!success) {
                    notifyError("Failed to refresh authentication token");
                }
            }
        });
    }

    /**
     * Checks if the current token is expired or about to expire
     */
    public boolean needsTokenRefresh() {
        return isTokenExpired();
    }

    /**
     * Method to be called when app resumes to reset any failure states
     */
    public void onAppResume() {
        resetFailureCounter();
        // Reload tokens in case they were updated by RestAuthManager
        reloadTokensFromPreferences();
    }
    /**
     * Forces a reload of tokens from SharedPreferences.
     * Useful when tokens might have been updated by another part of the app.
     */
    public void reloadTokensFromPreferences() {
        Log.d(TAG, "Reloading tokens from SharedPreferences");
        loadTokensFromPreferences();
    }

    /**
     * Gets the current token expiration time
     */
    public long getTokenExpirationTime() {
        return tokenExpirationTime;
    }

    // Add this method to reset failure counter when app resumes
    public void resetFailureCounter() {
        consecutiveAuthFailures = 0;
    }
    /**
     * Checks if we have valid authentication data
     */
    public boolean hasValidAuthData() {
        return !currentUserId.isEmpty() && !refreshToken.isEmpty();
    }

    public String getCurrentUserId() {
        return currentUserId;
    }

    public void cleanup() {
        stopPolling();
        processedSessions.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }
}