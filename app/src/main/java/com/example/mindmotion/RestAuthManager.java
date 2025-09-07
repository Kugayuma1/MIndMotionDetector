package com.example.mindmotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RestAuthManager {
    private static final String TAG = "RestAuthManager";
    private static final String FIREBASE_API_KEY = "AIzaSyC7bPi7suzy8DmMFSgP7n090t7zHXzI5Bk";
    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String AUTH_BASE_URL = "https://identitytoolkit.googleapis.com/v1/accounts";

    private ExecutorService executor;
    private Handler mainHandler;
    private Context context;
    private SharedPreferences prefs;

    public interface AuthListener {
        void onLoginSuccess(String userId);
        void onLoginFailed(String error);
    }

    public RestAuthManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void loginUser(String email, String password, AuthListener listener) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "Attempting Firebase Auth login for email: " + email);

                // Step 1: Authenticate with Firebase Auth REST API
                String authUrl = AUTH_BASE_URL + ":signInWithPassword?key=" + FIREBASE_API_KEY;

                JSONObject authPayload = new JSONObject();
                authPayload.put("email", email);
                authPayload.put("password", password);
                authPayload.put("returnSecureToken", true);

                HttpURLConnection authConnection = (HttpURLConnection) new URL(authUrl).openConnection();
                authConnection.setRequestMethod("POST");
                authConnection.setRequestProperty("Content-Type", "application/json");
                authConnection.setConnectTimeout(15000);
                authConnection.setReadTimeout(15000);
                authConnection.setDoOutput(true);

                try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(authConnection.getOutputStream())) {
                    writer.write(authPayload.toString());
                    writer.flush();
                }

                int authResponseCode = authConnection.getResponseCode();
                Log.d(TAG, "Firebase Auth response code: " + authResponseCode);

                if (authResponseCode == 200) {
                    // Parse auth response
                    BufferedReader reader = new BufferedReader(new InputStreamReader(authConnection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject authResponse = new JSONObject(response.toString());
                    String idToken = authResponse.getString("idToken");
                    String userId = authResponse.getString("localId"); // This is the Firebase Auth UID
                    String userEmail = authResponse.getString("email");

                    Log.d(TAG, "Firebase Auth successful for user: " + userId);

                    // Step 2: Get user profile directly using the UID (not email query)
                    getUserProfileByUid(idToken, userId, userEmail, listener);

                } else {
                    // Handle auth error
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(authConnection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

                    Log.e(TAG, "Firebase Auth failed: " + errorResponse.toString());

                    // Parse error message
                    final String errorMessage = parseAuthError(errorResponse.toString());
                    mainHandler.post(() -> listener.onLoginFailed(errorMessage));
                }

                authConnection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Login error", e);
                mainHandler.post(() -> listener.onLoginFailed("Network error. Please check your connection."));
            }
        });
    }

    private void getUserProfileByUid(String idToken, String userId, String email, AuthListener listener) {
        try {
            Log.d(TAG, "Getting user profile for userId: " + userId);

            // Direct document access using the authenticated user's UID
            String firestoreUrl = FIRESTORE_BASE_URL + "/users/" + userId;

            HttpURLConnection connection = (HttpURLConnection) new URL(firestoreUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Firestore GET response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject userDoc = new JSONObject(response.toString());

                if (userDoc.has("fields")) {
                    JSONObject fields = userDoc.getJSONObject("fields");

                    // Check user type
                    if (fields.has("userType") && fields.getJSONObject("userType").has("stringValue")) {
                        String userType = fields.getJSONObject("userType").getString("stringValue");
                        Log.d(TAG, "User type: " + userType);

                        if ("student".equals(userType)) {
                            // Store user info
                            String userName = "Student";
                            if (fields.has("name") && fields.getJSONObject("name").has("stringValue")) {
                                userName = fields.getJSONObject("name").getString("stringValue");
                            }

                            prefs.edit()
                                    .putString("USER_ID", userId)  // Store Firebase Auth UID
                                    .putString("USER_NAME", userName)
                                    .putString("FIREBASE_UID", userId)
                                    .putString("ID_TOKEN", idToken)
                                    .putString("USER_EMAIL", email)
                                    .apply();

                            Log.d(TAG, "Login successful for student: " + userId);
                            mainHandler.post(() -> listener.onLoginSuccess(userId));
                        } else {
                            Log.d(TAG, "User is not a student: " + userType);
                            mainHandler.post(() -> listener.onLoginFailed("Access denied. Students only."));
                        }
                    } else {
                        Log.e(TAG, "No userType field found");
                        mainHandler.post(() -> listener.onLoginFailed("Invalid user profile: missing user type"));
                    }
                } else {
                    Log.e(TAG, "No fields found in user document");
                    mainHandler.post(() -> listener.onLoginFailed("Invalid user profile: no data"));
                }
            } else if (responseCode == 404) {
                Log.e(TAG, "User document not found");
                mainHandler.post(() -> listener.onLoginFailed("User profile not found. Please contact administrator."));
            } else {
                // Read error response
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                errorReader.close();

                Log.e(TAG, "Firestore query failed with code: " + responseCode + ", response: " + errorResponse.toString());
                mainHandler.post(() -> listener.onLoginFailed("Error accessing user profile"));
            }

            connection.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Error getting user profile", e);
            mainHandler.post(() -> listener.onLoginFailed("Error verifying user profile"));
        }
    }

    // Extract error parsing logic into a separate method
    private String parseAuthError(String errorResponseString) {
        String errorMessage = "Invalid email or password";
        try {
            JSONObject errorJson = new JSONObject(errorResponseString);
            if (errorJson.has("error") && errorJson.getJSONObject("error").has("message")) {
                String firebaseError = errorJson.getJSONObject("error").getString("message");
                if (firebaseError.contains("INVALID_PASSWORD") || firebaseError.contains("EMAIL_NOT_FOUND")) {
                    errorMessage = "Invalid email or password";
                } else if (firebaseError.contains("USER_DISABLED")) {
                    errorMessage = "Account has been disabled";
                } else if (firebaseError.contains("TOO_MANY_ATTEMPTS")) {
                    errorMessage = "Too many failed attempts. Try again later.";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing auth error response", e);
        }
        return errorMessage;
    }

    public boolean isUserLoggedIn() {
        String userId = prefs.getString("USER_ID", "");
        String idToken = prefs.getString("ID_TOKEN", "");
        boolean loggedIn = !userId.isEmpty() && !idToken.isEmpty();
        Log.d(TAG, "Checking login status: " + loggedIn + " (userId: " + userId + ")");
        return loggedIn;
    }

    public String getCurrentUserId() {
        return prefs.getString("USER_ID", "");
    }

    public String getCurrentUserName() {
        return prefs.getString("USER_NAME", "Student");
    }

    public String getIdToken() {
        return prefs.getString("ID_TOKEN", "");
    }

    public String getCurrentUserEmail() {
        return prefs.getString("USER_EMAIL", "");
    }

    public void logout() {
        Log.d(TAG, "Logging out user: " + getCurrentUserId());
        prefs.edit()
                .remove("USER_ID")
                .remove("USER_NAME")
                .remove("FIREBASE_UID")
                .remove("ID_TOKEN")
                .remove("USER_EMAIL")
                .apply();
    }

    public void cleanup() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}