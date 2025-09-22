package com.example.mindmotion;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainMenuActivity extends AppCompatActivity implements RestAuthManager.TokenRefreshListener {
    private RestAuthManager authManager;
    private Handler tokenRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable tokenRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (authManager != null && authManager.isUserLoggedIn()) {
                authManager.refreshTokenIfNeeded(MainMenuActivity.this);
            }
            tokenRefreshHandler.postDelayed(this, 30 * 60 * 1000); // 30 minutes
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_menu);

        authManager = new RestAuthManager(this);
        initializeViews();
        setupWelcomeMessage();
        setupBackPressHandler();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPeriodicTokenRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPeriodicTokenRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPeriodicTokenRefresh();
        if (authManager != null) {
            authManager.cleanup();
        }
    }

    private void startPeriodicTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 5 * 60 * 1000); // 5 minutes initial delay
    }

    private void stopPeriodicTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
    }

    @Override
    public void onTokenRefreshSuccess() {
        // Background refresh successful - no action needed
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        if (error.contains("Session expired") || error.contains("No refresh token")) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_LONG).show();
                authManager.logout();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }
    }

    private void initializeViews() {
        TextView welcomeText = findViewById(R.id.welcome_text);
        ImageButton cameraButton = findViewById(R.id.camera_button);
        ImageButton logoutButton = findViewById(R.id.logout_button);

        if (cameraButton != null) {
            cameraButton.setOnClickListener(v -> openCamera());
        }

        if (logoutButton != null) {
            logoutButton.setOnClickListener(v -> showLogoutConfirmation());
        }
    }

    private void setupWelcomeMessage() {
        TextView welcomeText = findViewById(R.id.welcome_text);
        if (welcomeText == null) return;

        SharedPreferences prefs = getSharedPreferences("MindMotionPrefs", MODE_PRIVATE);
        String userName = prefs.getString("USER_NAME", "Student");
        welcomeText.setText("Welcome, " + userName + "!");
    }

    private void setupBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showLogoutConfirmation();
            }
        });
    }

    private void openCamera() {
        startActivity(new Intent(this, CameraActivity.class));
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> performLogout())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        stopPeriodicTokenRefresh();
        authManager.logout();
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}