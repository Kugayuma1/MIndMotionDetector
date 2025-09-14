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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;

public class RestAuthManager {
    private static final String TAG = "RestAuthManager";

    private static final String FIREBASE_API_KEY = "AIzaSyC7bPi7suzy8DmMFSgP7n090t7zHXzI5Bk";
    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String AUTH_BASE_URL = "https://identitytoolkit.googleapis.com/v1/accounts";
    private static final String SECURE_TOKEN_URL = "https://securetoken.googleapis.com/v1/token?key=" + FIREBASE_API_KEY;

    private ExecutorService executor;
    private Handler mainHandler;
    private Context context;
    private SharedPreferences prefs;

    public interface AuthListener {
        void onLoginSuccess(String userId);

        void onLoginFailed(String error);
    }

    public interface TokenRefreshListener {
        void onTokenRefreshSuccess();
        void onTokenRefreshFailed(String error);
    }
    public RestAuthManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // In RestAuthManager.java, update the loginUser method:

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
                    String refreshToken = authResponse.getString("refreshToken");
                    String userId = authResponse.getString("localId");
                    String userEmail = authResponse.getString("email");

                    // UPDATED: Calculate token expiration time dynamically
                    // Firebase Auth returns "expiresIn" as a string representing seconds
                    // Get the expires_in value from response, default to 3600 seconds (1 hour)
                    int expiresInSeconds = 3600; // Default 1 hour
                    if (authResponse.has("expiresIn")) {
                        try {
                            String expiresInStr = authResponse.getString("expiresIn");
                            expiresInSeconds = Integer.parseInt(expiresInStr);
                            Log.d(TAG, "Token expires in " + expiresInSeconds + " seconds");
                        } catch (Exception e) {
                            Log.w(TAG, "Could not parse expiresIn, using default", e);
                        }
                    }

                    // Use 90% of the actual expiry time as a buffer to ensure we refresh before actual expiry
                    // This prevents edge cases where the token expires between check and use
                    long bufferTimeMillis = (long)(expiresInSeconds * 0.9 * 1000);
                    long tokenExpirationTime = System.currentTimeMillis() + bufferTimeMillis;

                    Log.d(TAG, "Firebase Auth successful for user: " + userId);
                    Log.d(TAG, "Token will be considered expired at: " + new java.util.Date(tokenExpirationTime));
                    Log.d(TAG, "Actual expiry would be at: " + new java.util.Date(System.currentTimeMillis() + (expiresInSeconds * 1000L)));

                    // Step 2: Get user profile directly using the UID
                    getUserProfileByUid(idToken, refreshToken, tokenExpirationTime, userId, userEmail, listener);

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

    private void getUserProfileByUid(String idToken, String refreshToken, long tokenExpirationTime, String userId, String email, AuthListener listener) {
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

                            // Store all authentication data including refresh token and expiration time
                            prefs.edit()
                                    .putString("USER_ID", userId)
                                    .putString("USER_NAME", userName)
                                    .putString("FIREBASE_UID", userId)
                                    .putString("ID_TOKEN", idToken)
                                    .putString("REFRESH_TOKEN", refreshToken)
                                    .putString("USER_EMAIL", email)
                                    .putLong("TOKEN_EXPIRATION_TIME", tokenExpirationTime) // Store expiration time
                                    .apply();

                            Log.d(TAG, "Login successful for student: " + userId);
                            Log.d(TAG, "Token expiration stored: " + tokenExpirationTime);
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
        String refreshToken = prefs.getString("REFRESH_TOKEN", "");
        long tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        boolean hasBasicAuth = !userId.isEmpty() && !refreshToken.isEmpty();
        boolean tokenValid = !idToken.isEmpty() && tokenExpirationTime > System.currentTimeMillis();

        boolean loggedIn = hasBasicAuth && (tokenValid || !refreshToken.isEmpty());

        Log.d(TAG, "Checking login status:");
        Log.d(TAG, "  - Has basic auth: " + hasBasicAuth);
        Log.d(TAG, "  - Token valid: " + tokenValid);
        Log.d(TAG, "  - Token expiration: " + tokenExpirationTime + " (current: " + System.currentTimeMillis() + ")");
        Log.d(TAG, "  - Overall logged in: " + loggedIn);

        return loggedIn;
    }
    public void refreshTokenIfNeeded(TokenRefreshListener listener) {
        String refreshToken = prefs.getString("REFRESH_TOKEN", "");
        Log.d(TAG, "Using refresh token: " + refreshToken);
        long tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        if (refreshToken.isEmpty()) {
            Log.e(TAG, "No refresh token available");
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshFailed("No refresh token"));
            }
            return;
        }

        // Check if token is still valid (5 min buffer)
        if (System.currentTimeMillis() < (tokenExpirationTime - 300000)) {
            Log.d(TAG, "Token is still valid, no refresh needed");
            if (listener != null) {
                mainHandler.post(listener::onTokenRefreshSuccess);
            }
            return;
        }

        Log.d(TAG, "Token expired or expiring soon, refreshing...");

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String refreshUrl = "https://securetoken.googleapis.com/v1/token?key=" + FIREBASE_API_KEY;
                String payload = "grant_type=refresh_token&refresh_token=" + refreshToken;

                URL url = new URL(refreshUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Token refresh response code: " + responseCode);

                InputStream is = (responseCode == HttpURLConnection.HTTP_OK)
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    JSONObject refreshResponse = new JSONObject(response.toString());
                    String newIdToken = refreshResponse.getString("id_token");
                    String newRefreshToken = refreshResponse.getString("refresh_token");
                    long expiresInSeconds = refreshResponse.getLong("expires_in");

                    long newExpiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000);

                    // Save tokens
                    prefs.edit()
                            .putString("ID_TOKEN", newIdToken)
                            .putString("REFRESH_TOKEN", newRefreshToken)
                            .putLong("TOKEN_EXPIRATION_TIME", newExpiryTime)
                            .apply();

                    Log.d(TAG, "✅ Token refresh successful");
                    Log.d(TAG, "New ID token: " + newIdToken.substring(0, 20) + "...");
                    Log.d(TAG, "New refresh token: " + newRefreshToken.substring(0, 20) + "...");
                    Log.d(TAG, "Expires at: " + new java.util.Date(newExpiryTime));

                    if (listener != null) {
                        mainHandler.post(listener::onTokenRefreshSuccess);
                    }

                } else {
                    Log.e(TAG, "❌ Token refresh failed: " + response);
                    logout();
                    if (listener != null) {
                        mainHandler.post(() -> listener.onTokenRefreshFailed("Session expired. Please login again."));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Token refresh error", e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onTokenRefreshFailed("Network error during token refresh"));
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    // Enhanced method to get a valid token (refreshes if needed)
    public void getValidToken(TokenRefreshListener listener) {
        if (!isUserLoggedIn()) {
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshFailed("User not logged in"));
            }
            return;
        }

        String currentToken = prefs.getString("ID_TOKEN", "");
        long tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        // If token is still valid, return immediately
        if (!currentToken.isEmpty() && System.currentTimeMillis() < tokenExpirationTime) {
            Log.d(TAG, "Current token is still valid");
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshSuccess());
            }
            return;
        }

        // Token expired or missing, refresh it
        refreshTokenIfNeeded(listener);
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

    public String getRefreshToken() {
        return prefs.getString("REFRESH_TOKEN", "");
    }

    public String getCurrentUserEmail() {
        return prefs.getString("USER_EMAIL", "");
    }

    public long getTokenExpirationTime() {
        return prefs.getLong("TOKEN_EXPIRATION_TIME", 0);
    }

    public void logout() {
        Log.d(TAG, "Logging out user: " + getCurrentUserId());
        prefs.edit()
                .remove("USER_ID")
                .remove("USER_NAME")
                .remove("FIREBASE_UID")
                .remove("ID_TOKEN")
                .remove("REFRESH_TOKEN")
                .remove("USER_EMAIL")
                .remove("TOKEN_EXPIRATION_TIME") // Also clear expiration time
                .apply();
    }

    public void cleanup() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}