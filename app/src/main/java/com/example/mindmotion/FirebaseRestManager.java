package com.example.mindmotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
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
    private static final String FIREBASE_API_KEY = "AIzaSyC7bPi7suzy8DmMFSgP7n090t7zHXzI5Bk";
    private static final String AUTH_BASE_URL = "https://identitytoolkit.googleapis.com/v1/accounts";
    private static final int MAX_CONSECUTIVE_AUTH_FAILURES = 3;

    public interface SessionPollerListener {
        void onNewSessionFound(String sessionId, String motionType, String studentId);
        void onSessionTimedOut(String sessionId);
        void onError(String error);
        void onMotionMarked(String sessionId);
        void onVoiceDataSaved(String date, String data);
    }

    private ExecutorService executor;
    private Handler mainHandler;
    private SessionPollerListener listener;
    private boolean isPolling = false;
    private Set<String> processedSessions = new HashSet<>();

    // Auth state
    private String currentUserId, idToken, refreshToken;
    private long tokenExpirationTime;
    private Context context;
    private volatile boolean isRefreshingToken = false;
    private final Object refreshLock = new Object();
    private CountDownLatch refreshLatch = null;
    private int consecutiveAuthFailures = 0;

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
    }

    public void setListener(SessionPollerListener listener) {
        this.listener = listener;
    }

    public void startPollingForSessions() {
        if (isPolling || currentUserId.isEmpty() || refreshToken.isEmpty()) {
            if (currentUserId.isEmpty() || refreshToken.isEmpty()) {
                notifyError("User not authenticated");
            }
            return;
        }

        consecutiveAuthFailures = 0;
        isPolling = true;
        pollForSessions();
    }

    public void stopPolling() {
        isPolling = false;
    }

    private boolean isTokenExpired() {
        return tokenExpirationTime <= (System.currentTimeMillis() + (5 * 60 * 1000));
    }

    private boolean ensureValidToken() {
        if (!isTokenExpired()) return true;

        synchronized (refreshLock) {
            if (!isTokenExpired()) return true;

            if (isRefreshingToken) {
                try {
                    if (refreshLatch != null) {
                        return refreshLatch.await(15, TimeUnit.SECONDS) && !isTokenExpired();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            isRefreshingToken = true;
            refreshLatch = new CountDownLatch(1);

            try {
                return refreshIdTokenSync();
            } finally {
                isRefreshingToken = false;
                if (refreshLatch != null) refreshLatch.countDown();
            }
        }
    }

    private boolean refreshIdTokenSync() {
        if (refreshToken.isEmpty()) {
            consecutiveAuthFailures++;
            return false;
        }

        try {
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

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject refreshResponse = new JSONObject(response.toString());
                idToken = refreshResponse.getString("id_token");
                refreshToken = refreshResponse.optString("refresh_token", refreshToken);
                int expiresIn = refreshResponse.optInt("expires_in", 3600);

                long bufferTime = (long)(expiresIn * 0.9 * 1000);
                tokenExpirationTime = System.currentTimeMillis() + bufferTime;

                saveTokensToPreferences();
                consecutiveAuthFailures = 0;
                connection.disconnect();
                return true;

            } else {
                consecutiveAuthFailures++;
                if (responseCode == 400) {
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

                    JSONObject errorJson = new JSONObject(errorResponse.toString());
                    if (errorJson.has("error")) {
                        JSONObject error = errorJson.getJSONObject("error");
                        String errorMessage = error.optString("message", "");

                        if (errorMessage.contains("INVALID_REFRESH_TOKEN") ||
                                errorMessage.contains("TOKEN_EXPIRED")) {
                            clearAuthData();
                            consecutiveAuthFailures = MAX_CONSECUTIVE_AUTH_FAILURES;
                        }
                    }
                }
                connection.disconnect();
                return false;
            }

        } catch (Exception e) {
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
                if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                    notifyError("Authentication failed repeatedly - please login again");
                    stopPolling();
                    return;
                }

                if (!ensureValidToken()) {
                    if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                        notifyError("Authentication expired - please login again");
                        stopPolling();
                    } else {
                        if (isPolling) {
                            mainHandler.postDelayed(this::pollForSessions, 5000);
                        }
                    }
                    return;
                }

                // NEW: Query user-specific motion sessions instead of global collection
                String queryUrl = BASE_URL + "/users/" + currentUserId + "/motion_sessions" +
                        "?pageSize=50&orderBy=timestamp%20desc";

                HttpURLConnection connection = (HttpURLConnection) new URL(queryUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + idToken);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    parseUserScopedResponse(response.toString());
                    consecutiveAuthFailures = 0;

                } else if (responseCode == 401 || responseCode == 403) {
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0; // Force refresh
                    }
                    if (ensureValidToken() && isPolling) {
                        pollForSessions();
                        return;
                    } else if (consecutiveAuthFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                        notifyError("Authentication failed - please login again");
                        stopPolling();
                    }
                    return;
                } else if (responseCode < 500) {
                    notifyError("Query failed: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                if (!(e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException)) {
                    notifyError("Polling error: " + e.getMessage());
                }
            }

            if (isPolling) {
                mainHandler.postDelayed(this::pollForSessions, 3000);
            }
        });
    }

    public void markMotionDetected(String sessionId) {
        executor.execute(() -> {
            try {
                if (!ensureValidToken()) {
                    notifyError("Authentication expired - please login again");
                    return;
                }

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

                // NEW: Update user-scoped session path
                String urlString = BASE_URL + "/users/" + currentUserId + "/motion_sessions/" + sessionId;

                HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
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
                    mainHandler.post(() -> {
                        if (listener != null) listener.onMotionMarked(sessionId);
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0;
                    }
                    if (ensureValidToken()) {
                        markMotionDetected(sessionId);
                    } else {
                        notifyError("Authentication failed - please login again");
                    }
                } else {
                    notifyError("Failed to update session: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                notifyError("Update error: " + e.getMessage());
            }
        });
    }

    // Updated parsing method for user-scoped sessions
    private void parseUserScopedResponse(String jsonResponse) {
        try {
            JSONObject response = new JSONObject(jsonResponse);
            if (!response.has("documents")) return;

            JSONArray documents = response.getJSONArray("documents");

            for (int i = 0; i < documents.length(); i++) {
                JSONObject doc = documents.getJSONObject(i);
                String documentPath = doc.getString("name");
                String sessionId = extractSessionIdFromPath(documentPath);

                if (processedSessions.contains(sessionId)) continue;

                JSONObject fields = doc.getJSONObject("fields");

                String motionType = null;
                String status = null;
                long timestamp = 0;

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

                // Since we're already querying user-specific sessions, no need to check studentId
                if (!"waiting".equals(status)) continue;

                long timestampMs = timestamp * 1000;
                if (timestamp > 0 && isSessionExpired(timestampMs)) {
                    markSessionAsTimedOut(sessionId);
                    continue;
                }

                processedSessions.add(sessionId);

                if (motionType != null) {
                    final String finalSessionId = sessionId;
                    final String finalMotionType = motionType;

                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onNewSessionFound(finalSessionId, finalMotionType, currentUserId);
                        }
                    });
                }
            }

        } catch (Exception e) {
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
                if (!ensureValidToken()) return;

                JSONObject payload = new JSONObject();
                JSONObject fields = new JSONObject();

                JSONObject statusField = new JSONObject();
                statusField.put("stringValue", "timeout");
                fields.put("status", statusField);

                JSONObject timedOutAtField = new JSONObject();
                timedOutAtField.put("integerValue", String.valueOf(System.currentTimeMillis()));
                fields.put("timedOutAt", timedOutAtField);

                payload.put("fields", fields);

                // NEW: Update user-scoped session path
                String urlString = BASE_URL + "/users/" + currentUserId + "/motion_sessions/" + sessionId;

                HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
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
                    mainHandler.post(() -> {
                        if (listener != null) listener.onSessionTimedOut(sessionId);
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0;
                    }
                    if (ensureValidToken()) {
                        markSessionAsTimedOut(sessionId);
                    }
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error marking session as timed out", e);
            }
        });
    }

    public void saveVoiceData(String spokenText) {
        if (currentUserId.isEmpty()) return;

        executor.execute(() -> {
            try {
                if (!ensureValidToken()) {
                    notifyError("Authentication expired - please login again");
                    return;
                }

                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                String documentId = currentUserId + "_" + today;

                // NEW: Use user-scoped voice data path
                String getUrlString = BASE_URL + "/users/" + currentUserId + "/voice_data/" + documentId;
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

                HttpURLConnection connection = (HttpURLConnection) new URL(getUrlString).openConnection();
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
                    mainHandler.post(() -> {
                        if (listener != null) listener.onVoiceDataSaved(today, spokenText);
                    });
                } else if (responseCode == 401 || responseCode == 403) {
                    synchronized (refreshLock) {
                        tokenExpirationTime = 0;
                    }
                    if (ensureValidToken()) {
                        saveVoiceData(spokenText);
                    } else {
                        notifyError("Authentication failed - please login again");
                    }
                }

                connection.disconnect();

            } catch (Exception e) {
                notifyError("Voice data save error: " + e.getMessage());
            }
        });
    }

    private JSONObject getExistingVoiceData(String urlString) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
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
            // No existing data or error
        }
        return null;
    }

    private boolean isSessionExpired(long timestamp) {
        return (System.currentTimeMillis() - timestamp) > 70000;
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(error);
        });
    }

    public void reloadTokensFromPreferences() {
        loadTokensFromPreferences();
    }

    public void onAppResume() {
        consecutiveAuthFailures = 0;
        reloadTokensFromPreferences();
    }

    public void cleanup() {
        stopPolling();
        processedSessions.clear();
        if (executor != null) executor.shutdown();
    }
}