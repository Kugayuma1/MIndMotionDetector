package com.example.mindmotion;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 2000; // 2 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Show splash screen then check authentication
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkAuthenticationAndProceed();
        }, SPLASH_DURATION);
    }

    private void checkAuthenticationAndProceed() {
        SharedPreferences prefs = getSharedPreferences("MindMotionPrefs", MODE_PRIVATE);
        String userId = prefs.getString("USER_ID", "");

        if (userId.isEmpty()) {
            // Not logged in, go to login
            startActivity(new Intent(this, LoginActivity.class));
        } else {
            // Already logged in, go to main menu
            startActivity(new Intent(this, MainMenuActivity.class));
        }
        finish();
    }
}