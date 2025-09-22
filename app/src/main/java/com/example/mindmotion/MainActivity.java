
package com.example.mindmotion;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements RestAuthManager.TokenRefreshListener {
    private static final int SPLASH_DURATION = 2000;
    private RestAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = new RestAuthManager(this);

        new Handler(Looper.getMainLooper()).postDelayed(this::checkAuthenticationAndProceed, SPLASH_DURATION);
    }

    private void checkAuthenticationAndProceed() {
        if (!authManager.isUserLoggedIn()) {
            goToLogin();
            return;
        }
        authManager.getValidToken(this);
    }

    @Override
    public void onTokenRefreshSuccess() {
        goToMainMenu();
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        if (error.contains("Network error")) {
            Toast.makeText(this, "Connection error. Please check your internet.", Toast.LENGTH_LONG).show();
        }
        goToLogin();
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void goToMainMenu() {
        startActivity(new Intent(this, MainMenuActivity.class));
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