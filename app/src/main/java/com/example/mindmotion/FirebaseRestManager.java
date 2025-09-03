package com.example.mindmotion;

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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FirebaseRestManager {
    private static final String TAG = "FirebaseRestManager";

    // Your Firebase project details
    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String COLLECTION_NAME = "motion_sessions";

    private ExecutorService executor;
    private Handler mainHandler;
    private SessionPollerListener listener;
    private boolean isPolling = false;
    private Set<String> processedSessions = new HashSet<>();

    public interface SessionPollerListener {
        void onNewSessionFound(String sessionId, String motionType, String studentId);
        void onSessionTimedOut(String sessionId);
        void onError(String error);
        void onMotionMarked(String sessionId);
    }

    public FirebaseRestManager() {
        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(SessionPollerListener listener) {
        this.listener = listener;
    }

    public void startPollingForSessions() {
        if (isPolling) return;

        isPolling = true;
        Log.d(TAG, "Starting to poll for motion sessions...");
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
                // FIXED: Remove API key - use unauthenticated access as per Firestore rules
                String urlString = BASE_URL + "/" + COLLECTION_NAME;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

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
                    Log.d(TAG, "Poll response: " + responseBody.substring(0, Math.min(responseBody.length(), 200)) + "...");
                    parseAndProcessSessions(responseBody);
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
                    notifyError("HTTP Error: " + responseCode);
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error polling sessions", e);
                notifyError("Polling error: " + e.getMessage());
            }

            // Continue polling every 3 seconds
            if (isPolling) {
                mainHandler.postDelayed(this::pollForSessions, 3000);
            }
        });
    }

    private void parseAndProcessSessions(String jsonResponse) {
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

                // Skip if already processed
                if (processedSessions.contains(sessionId)) {
                    continue;
                }

                JSONObject fields = doc.getJSONObject("fields");

                // Check if status is "waiting"
                if (fields.has("status") && fields.getJSONObject("status").has("stringValue")) {
                    String status = fields.getJSONObject("status").getString("stringValue");
                    Log.d(TAG, "Session " + sessionId + " has status: " + status);

                    if ("waiting".equals(status)) {
                        String motionType = null;
                        String studentId = null;
                        long timestamp = 0;

                        // Extract motion type
                        if (fields.has("motionType") && fields.getJSONObject("motionType").has("stringValue")) {
                            motionType = fields.getJSONObject("motionType").getString("stringValue");
                        }

                        // Extract student ID
                        if (fields.has("studentId") && fields.getJSONObject("studentId").has("stringValue")) {
                            studentId = fields.getJSONObject("studentId").getString("stringValue");
                        }

                        // Extract timestamp
                        if (fields.has("timestamp") && fields.getJSONObject("timestamp").has("integerValue")) {
                            String timestampStr = fields.getJSONObject("timestamp").getString("integerValue");
                            timestamp = Long.parseLong(timestampStr) * 1000; // Convert to milliseconds
                        }

                        // Check if session is expired
                        if (timestamp > 0 && isSessionExpired(timestamp)) {
                            Log.d(TAG, "Session expired: " + sessionId);
                            markSessionAsTimedOut(sessionId);
                            continue;
                        }

                        // Mark as processed to avoid duplicate notifications
                        processedSessions.add(sessionId);

                        // Notify listener
                        if (motionType != null && studentId != null) {
                            final String finalSessionId = sessionId;
                            final String finalMotionType = motionType;
                            final String finalStudentId = studentId;

                            Log.d(TAG, "Found new session: " + finalSessionId + " motion: " + finalMotionType);

                            mainHandler.post(() -> {
                                if (listener != null) {
                                    listener.onNewSessionFound(finalSessionId, finalMotionType, finalStudentId);
                                }
                            });
                        }
                    }
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing response", e);
            notifyError("JSON parsing error: " + e.getMessage());
        }
    }

    private String extractSessionIdFromPath(String documentPath) {
        // Extract session ID from path like: projects/PROJECT_ID/databases/(default)/documents/motion_sessions/SESSION_ID
        String[] parts = documentPath.split("/");
        return parts[parts.length - 1];
    }

    public void markMotionDetected(String sessionId) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "Marking motion detected for session: " + sessionId);

                // Create update payload
                JSONObject payload = new JSONObject();
                JSONObject fields = new JSONObject();

                // Set detected to true
                JSONObject detectedField = new JSONObject();
                detectedField.put("booleanValue", true);
                fields.put("detected", detectedField);

                // Set status to completed
                JSONObject statusField = new JSONObject();
                statusField.put("stringValue", "completed");
                fields.put("status", statusField);

                // Set completed timestamp
                JSONObject completedAtField = new JSONObject();
                completedAtField.put("integerValue", String.valueOf(System.currentTimeMillis()));
                fields.put("completedAt", completedAtField);

                payload.put("fields", fields);

                // Make PATCH request
                String urlString = BASE_URL + "/" + COLLECTION_NAME + "/" + sessionId;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PATCH");
                connection.setRequestProperty("Content-Type", "application/json");
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
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

                    Log.e(TAG, "Failed to update session " + responseCode + ": " + errorResponse.toString());
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

                String urlString = BASE_URL + "/" + COLLECTION_NAME + "/" + sessionId;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PATCH");
                connection.setRequestProperty("Content-Type", "application/json");
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

    public void cleanup() {
        stopPolling();
        processedSessions.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }
}