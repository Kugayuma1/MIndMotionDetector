package com.example.mindmotion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RestAuthManager {
    private static final String TAG = "RestAuthManager";
    private static final String FIREBASE_API_KEY = "AIzaSyC7bPi7suzy8DmMFSgP7n090t7zHXzI5Bk";
    private static final String PROJECT_ID = "mindmotion-55c99";
    private static final String FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents";
    private static final String AUTH_BASE_URL = "https://identitytoolkit.googleapis.com/v1/accounts";

    public interface AuthListener {
        void onLoginSuccess(String userId);
        void onLoginFailed(String error);
    }

    public interface TokenRefreshListener {
        void onTokenRefreshSuccess();
        void onTokenRefreshFailed(String error);
    }

    private ExecutorService executor;
    private Handler mainHandler;
    private Context context;
    private SharedPreferences prefs;

    public RestAuthManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("MindMotionPrefs", Context.MODE_PRIVATE);
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void loginUser(String email, String password, AuthListener listener) {
        executor.execute(() -> {
            try {
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

                try (OutputStreamWriter writer = new OutputStreamWriter(authConnection.getOutputStream())) {
                    writer.write(authPayload.toString());
                    writer.flush();
                }

                int authResponseCode = authConnection.getResponseCode();

                if (authResponseCode == 200) {
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

                    int expiresInSeconds = 3600;
                    if (authResponse.has("expiresIn")) {
                        try {
                            expiresInSeconds = Integer.parseInt(authResponse.getString("expiresIn"));
                        } catch (Exception e) {
                            Log.w(TAG, "Could not parse expiresIn, using default");
                        }
                    }

                    long bufferTimeMillis = (long)(expiresInSeconds * 0.9 * 1000);
                    long tokenExpirationTime = System.currentTimeMillis() + bufferTimeMillis;

                    getUserProfileByUid(idToken, refreshToken, tokenExpirationTime, userId, userEmail, listener);

                } else {
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(authConnection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();

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
            String firestoreUrl = FIRESTORE_BASE_URL + "/users/" + userId;

            HttpURLConnection connection = (HttpURLConnection) new URL(firestoreUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int responseCode = connection.getResponseCode();

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

                    if (fields.has("userType") && fields.getJSONObject("userType").has("stringValue")) {
                        String userType = fields.getJSONObject("userType").getString("stringValue");

                        if ("student".equals(userType)) {
                            String userName = "Student";
                            if (fields.has("name") && fields.getJSONObject("name").has("stringValue")) {
                                userName = fields.getJSONObject("name").getString("stringValue");
                            }

                            prefs.edit()
                                    .putString("USER_ID", userId)
                                    .putString("USER_NAME", userName)
                                    .putString("FIREBASE_UID", userId)
                                    .putString("ID_TOKEN", idToken)
                                    .putString("REFRESH_TOKEN", refreshToken)
                                    .putString("USER_EMAIL", email)
                                    .putLong("TOKEN_EXPIRATION_TIME", tokenExpirationTime)
                                    .apply();

                            mainHandler.post(() -> listener.onLoginSuccess(userId));
                        } else {
                            mainHandler.post(() -> listener.onLoginFailed("Access denied. Students only."));
                        }
                    } else {
                        mainHandler.post(() -> listener.onLoginFailed("Invalid user profile: missing user type"));
                    }
                } else {
                    mainHandler.post(() -> listener.onLoginFailed("Invalid user profile: no data"));
                }
            } else if (responseCode == 404) {
                mainHandler.post(() -> listener.onLoginFailed("User profile not found. Please contact administrator."));
            } else {
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                errorReader.close();

                mainHandler.post(() -> listener.onLoginFailed("Error accessing user profile"));
            }

            connection.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Error getting user profile", e);
            mainHandler.post(() -> listener.onLoginFailed("Error verifying user profile"));
        }
    }

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

        return hasBasicAuth && (tokenValid || !refreshToken.isEmpty());
    }

    public void refreshTokenIfNeeded(TokenRefreshListener listener) {
        String refreshToken = prefs.getString("REFRESH_TOKEN", "");
        long tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        if (refreshToken.isEmpty()) {
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshFailed("No refresh token"));
            }
            return;
        }

        // Check if token is still valid (5 min buffer)
        if (System.currentTimeMillis() < (tokenExpirationTime - 300000)) {
            if (listener != null) {
                mainHandler.post(listener::onTokenRefreshSuccess);
            }
            return;
        }

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String refreshUrl = "https://securetoken.googleapis.com/v1/token?key=" + FIREBASE_API_KEY;
                String payload = "grant_type=refresh_token&refresh_token=" + refreshToken;

                connection = (HttpURLConnection) new URL(refreshUrl).openConnection();
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

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        responseCode == HttpURLConnection.HTTP_OK ? connection.getInputStream() : connection.getErrorStream()));
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

                    prefs.edit()
                            .putString("ID_TOKEN", newIdToken)
                            .putString("REFRESH_TOKEN", newRefreshToken)
                            .putLong("TOKEN_EXPIRATION_TIME", newExpiryTime)
                            .apply();

                    if (listener != null) {
                        mainHandler.post(listener::onTokenRefreshSuccess);
                    }

                } else {
                    logout();
                    if (listener != null) {
                        mainHandler.post(() -> listener.onTokenRefreshFailed("Session expired. Please login again."));
                    }
                }

            } catch (Exception e) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onTokenRefreshFailed("Network error during token refresh"));
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void getValidToken(TokenRefreshListener listener) {
        if (!isUserLoggedIn()) {
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshFailed("User not logged in"));
            }
            return;
        }

        String currentToken = prefs.getString("ID_TOKEN", "");
        long tokenExpirationTime = prefs.getLong("TOKEN_EXPIRATION_TIME", 0);

        if (!currentToken.isEmpty() && System.currentTimeMillis() < tokenExpirationTime) {
            if (listener != null) {
                mainHandler.post(() -> listener.onTokenRefreshSuccess());
            }
            return;
        }

        refreshTokenIfNeeded(listener);
    }

    // Getter methods
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
        prefs.edit()
                .remove("USER_ID")
                .remove("USER_NAME")
                .remove("FIREBASE_UID")
                .remove("ID_TOKEN")
                .remove("REFRESH_TOKEN")
                .remove("USER_EMAIL")
                .remove("TOKEN_EXPIRATION_TIME")
                .apply();
    }

    public void cleanup() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}