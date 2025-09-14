package com.example.mindmotion;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements RestAuthManager.TokenRefreshListener {
    private static final String TAG = "MainActivity";
    private static final int SPLASH_DURATION = 2000; // 2 seconds

    private RestAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = new RestAuthManager(this);


        // Show splash screen then check authentication
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkAuthenticationAndProceed();
        }, SPLASH_DURATION);
    }

    private void checkAuthenticationAndProceed() {
        Log.d(TAG, "Checking authentication status...");

        // Use the proper authentication check that considers token expiration
        if (!authManager.isUserLoggedIn()) {
            Log.d(TAG, "User not logged in, redirecting to login");
            goToLogin();
            return;
        }

        Log.d(TAG, "User appears to be logged in, checking/refreshing token...");

        // User has login data, but we need to ensure the token is valid
        authManager.getValidToken(this);
    }

    // TokenRefreshListener implementation
    @Override
    public void onTokenRefreshSuccess() {
        Log.d(TAG, "Token is valid/refreshed successfully, proceeding to main menu");
        goToMainMenu();
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        Log.e(TAG, "Token refresh failed: " + error);

        // Show error message if it's a network issue
        if (error.contains("Network error")) {
            Toast.makeText(this, "Connection error. Please check your internet.", Toast.LENGTH_LONG).show();
        }

        // Redirect to login for re-authentication
        goToLogin();
    }

    private void goToLogin() {
        Log.d(TAG, "Redirecting to LoginActivity");
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private void goToMainMenu() {
        Log.d(TAG, "Redirecting to MainMenuActivity");
        Intent intent = new Intent(this, MainMenuActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (authManager != null) {
            authManager.cleanup();
        }
    }
}