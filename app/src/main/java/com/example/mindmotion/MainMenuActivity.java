package com.example.mindmotion;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainMenuActivity extends AppCompatActivity implements RestAuthManager.TokenRefreshListener {
    private static final String TAG = "MainMenuActivity";

    private TextView welcomeText;
    private ImageButton cameraButton;
    private ImageButton logoutButton;

    private RestAuthManager authManager;

    // Token refresh components
    private Handler tokenRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable tokenRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Performing periodic token refresh check");
            if (authManager != null && authManager.isUserLoggedIn()) {
                authManager.refreshTokenIfNeeded(MainMenuActivity.this); // Use activity as listener
            }
            // Schedule next refresh in 30 minutes
            tokenRefreshHandler.postDelayed(this, 30 * 60 * 1000); // 30 minutes
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainMenuActivity onCreate called");
        setContentView(R.layout.activity_main_menu);

        authManager = new RestAuthManager(this);

        initializeViews();
        setupWelcomeMessage();
        setupBackPressHandler();

        Log.d(TAG, "MainMenuActivity setup completed");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainMenuActivity onResume - starting periodic token refresh");

        // Start periodic token refresh when activity becomes active
        startPeriodicTokenRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "MainMenuActivity onPause - stopping periodic token refresh");

        // Stop periodic token refresh when activity is not visible
        stopPeriodicTokenRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainMenuActivity onDestroy");

        // Clean up
        stopPeriodicTokenRefresh();
        if (authManager != null) {
            authManager.cleanup();
        }
    }

    private void startPeriodicTokenRefresh() {
        Log.d(TAG, "Starting periodic token refresh");

        // Remove any existing callbacks to prevent duplicates
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);

        // Start the periodic refresh (first check after 5 minutes, then every 30 minutes)
        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 5 * 60 * 1000); // 5 minutes initial delay
    }

    private void stopPeriodicTokenRefresh() {
        Log.d(TAG, "Stopping periodic token refresh");
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
    }

    // TokenRefreshListener implementation
    @Override
    public void onTokenRefreshSuccess() {
        Log.d(TAG, "Periodic token refresh successful");
        // No need to show message for background refresh
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        Log.e(TAG, "Periodic token refresh failed: " + error);

        if (error.contains("Session expired") || error.contains("No refresh token")) {
            // Critical failure - user needs to re-authenticate
            runOnUiThread(() -> {
                Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_LONG).show();

                // Force logout and redirect to login
                authManager.logout();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }
        // For network errors, just log and try again next cycle
    }

    private void initializeViews() {
        Log.d(TAG, "Initializing views in MainMenuActivity");

        welcomeText = findViewById(R.id.welcome_text);
        cameraButton = findViewById(R.id.camera_button);
        logoutButton = findViewById(R.id.logout_button);

        // Check if views were found
        if (welcomeText == null) Log.e(TAG, "welcome_text not found in layout");
        if (cameraButton == null) Log.e(TAG, "camera_button not found in layout");
        if (logoutButton == null) Log.e(TAG, "logout_button not found in layout");

        if (cameraButton != null) {
            cameraButton.setOnClickListener(v -> openCamera());
        }

        if (logoutButton != null) {
            logoutButton.setOnClickListener(v -> showLogoutConfirmation());
        }
    }

    private void setupWelcomeMessage() {
        Log.d(TAG, "Setting up welcome message");

        SharedPreferences prefs = getSharedPreferences("MindMotionPrefs", MODE_PRIVATE);
        String userId = prefs.getString("USER_ID", "");
        String userName = prefs.getString("USER_NAME", "");

        Log.d(TAG, "Retrieved from SharedPreferences - USER_ID: " + userId + ", USER_NAME: " + userName);

        String displayName = "Student"; // Default fallback

        // Use USER_NAME if available, otherwise use a default
        if (!userName.isEmpty()) {
            displayName = userName;
        } else if (!userId.isEmpty()) {
            // If we have userId but no userName, use a generic greeting
            displayName = "Student";
        }

        Log.d(TAG, "Setting welcome text to: Welcome, " + displayName + "!");

        if (welcomeText != null) {
            welcomeText.setText("Welcome, " + displayName + "!");
        } else {
            Log.e(TAG, "welcomeText is null, cannot set welcome message");
        }
    }

    private void setupBackPressHandler() {
        Log.d(TAG, "Setting up back press handler");
        // Modern way to handle back press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "Back button pressed, showing logout confirmation");
                // Prevent going back to splash screen
                // Show logout confirmation instead
                showLogoutConfirmation();
            }
        });
    }

    private void openCamera() {
        Log.d(TAG, "Opening camera");
        Intent intent = new Intent(this, CameraActivity.class);
        startActivity(intent);
        // Don't finish() here - let user return to main menu
    }

    private void showLogoutConfirmation() {
        Log.d(TAG, "Showing logout confirmation dialog");
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    Log.d(TAG, "User confirmed logout");
                    performLogout();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Log.d(TAG, "User cancelled logout");
                })
                .show();
    }

    private void performLogout() {
        Log.d(TAG, "Performing logout");

        // Stop token refresh before logout
        stopPeriodicTokenRefresh();

        // Use RestAuthManager for proper logout
        authManager.logout();

        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();

        // Go back to login screen
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        Log.d(TAG, "Started LoginActivity, finishing MainMenuActivity");
        finish();
    }
}