package com.example.mindmotion;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainMenuActivity extends AppCompatActivity {
    private static final String TAG = "MainMenuActivity";

    private TextView welcomeText;
    private ImageButton cameraButton;
    private ImageButton logoutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainMenuActivity onCreate called");
        setContentView(R.layout.activity_main_menu);

        initializeViews();
        setupWelcomeMessage();
        setupBackPressHandler();

        Log.d(TAG, "MainMenuActivity setup completed");
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

        // Use RestAuthManager for proper logout
        RestAuthManager authManager = new RestAuthManager(this);
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