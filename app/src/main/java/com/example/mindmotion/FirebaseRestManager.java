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
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FirebaseRestManager {
    private static final String TAG = "FirebaseRestManager";

    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String MOTION_SESSIONS_COLLECTION = "motion_sessions";
    private static final String VOICE_DATA_COLLECTION = "voice_data";

    private ExecutorService executor;
    private Handler mainHandler;
    private SessionPollerListener listener;
    private boolean isPolling = false;
    private Set<String> processedSessions = new HashSet<>();

    // User authentication
    private String currentUserId;
    private String idToken;
    private Context context;

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

        // Get user ID and token from SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        currentUserId = prefs.getString("USER_ID", "");
        idToken = prefs.getString("ID_TOKEN", "");

        Log.d(TAG, "FirebaseRestManager initialized for user: " + currentUserId);
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

        if (idToken.isEmpty()) {
            Log.e(TAG, "No authentication token found. Cannot start polling.");
            notifyError("Authentication token missing");
            return;
        }

        isPolling = true;
        Log.d(TAG, "Starting to poll for motion sessions for user: " + currentUserId);
        pollForSessions();
    }

    public void stopPolling() {
        isPolling = false;
        Log.d(TAG, "Stopping session polling");
    }

    private void pollForSessions() {
        if (!isPolling) return;

        executor.execute(() -> {
            try {
                // Use simple list documents with ordering
                String queryUrl = BASE_URL + "/" + MOTION_SESSIONS_COLLECTION
                        + "?pageSize=50"
                        + "&orderBy=timestamp%20desc";

                Log.d(TAG, "Query URL: " + queryUrl);

                // Make the HTTP request
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
                    Log.d(TAG, "Poll response: " + responseBody);
                    parseSimpleResponse(responseBody); // Use parseSimpleResponse for list documents

                } else {
                    // Read error response
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

                    Log.e(TAG, "HTTP Error " + responseCode + ": " + errorResponse.toString());
                    notifyError("Query failed: " + responseCode + " - " + errorResponse.toString());
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error polling sessions", e);
                notifyError("Polling error: " + e.getMessage());
            }

            if (isPolling) {
                mainHandler.postDelayed(this::pollForSessions, 3000);
            }
        });
    }

    // Added the missing parseQueryResponse method for runQuery responses
    private void parseQueryResponse(String jsonResponse) {
        try {
            JSONArray responseArray = new JSONArray(jsonResponse);
            Log.d(TAG, "Found " + responseArray.length() + " query results");

            for (int i = 0; i < responseArray.length(); i++) {
                JSONObject queryResult = responseArray.getJSONObject(i);

                if (!queryResult.has("document")) {
                    continue; // Skip if no document in this result
                }

                JSONObject doc = queryResult.getJSONObject("document");
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

                // Verify user and status (client-side filtering since we can't use complex queries easily)
                if (!currentUserId.equals(sessionUserId)) {
                    Log.d(TAG, "Skipping session " + sessionId + " - user mismatch (expected: " + currentUserId + ", got: " + sessionUserId + ")");
                    continue;
                }

                if (!"waiting".equals(status)) {
                    Log.d(TAG, "Skipping session " + sessionId + " - status: " + status);
                    continue;
                }

                // Check if session is expired (convert timestamp to milliseconds)
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
            Log.e(TAG, "Error parsing query response", e);
            notifyError("Response parsing error: " + e.getMessage());
        }
    }

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
                    Log.d(TAG, "Skipping session " + sessionId + " - user mismatch (expected: " + currentUserId + ", got: " + sessionUserId + ")");
                    continue;
                }

                if (!"waiting".equals(status)) {
                    Log.d(TAG, "Skipping session " + sessionId + " - status: " + status);
                    continue;
                }

                // Check if session is expired (convert timestamp to milliseconds)
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

    // New method to save voice data
    public void saveVoiceData(String spokenText) {
        if (currentUserId.isEmpty()) {
            Log.e(TAG, "Cannot save voice data: No user authenticated");
            return;
        }

        executor.execute(() -> {
            try {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                String documentId = currentUserId + "_" + today;

                Log.d(TAG, "Saving voice data for user: " + currentUserId + " on date: " + today);

                // First, try to get existing document
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

                // Add new word
                JSONObject newWord = new JSONObject();
                JSONObject newWordValue = new JSONObject();
                newWordValue.put("stringValue", spokenText);
                newWord.put("value", newWordValue);
                newWord.put("timestamp", new JSONObject().put("integerValue", System.currentTimeMillis()));
                wordsArray.put(newWord);

                // Create update payload
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

                // Use PATCH to update
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

    private String extractSessionIdFromPath(String documentPath) {
        String[] parts = documentPath.split("/");
        return parts[parts.length - 1];
    }

    public void markMotionDetected(String sessionId) {
        executor.execute(() -> {
            try {
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

    public void markSessionAsTimedOut(String sessionId) {
        executor.execute(() -> {
            try {
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
                } else {
                    Log.e(TAG, "Failed to timeout session. Response code: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error marking session as timed out", e);
            }
        });
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