package com.example.mindmotion;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity implements RestAuthManager.AuthListener {
    private static final String TAG = "LoginActivity";

    private EditText emailEditText;
    private EditText passwordEditText;
    private ImageButton loginButton;
    private ProgressBar progressBar;

    private RestAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "LoginActivity onCreate called");
        setContentView(R.layout.activity_login);

        authManager = new RestAuthManager(this);

        // Check if user is already logged in
        boolean isLoggedIn = authManager.isUserLoggedIn();
        Log.d(TAG, "User logged in status: " + isLoggedIn);

        if (isLoggedIn) {
            Log.d(TAG, "User already logged in, proceeding to main menu");
            proceedToMainMenu();
            return;
        }

        Log.d(TAG, "User not logged in, showing login form");
        initializeViews();
    }

    private void initializeViews() {
        Log.d(TAG, "Initializing views");
        emailEditText = findViewById(R.id.et_email);
        passwordEditText = findViewById(R.id.et_password);
        loginButton = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);

        loginButton.setOnClickListener(v -> loginUser());
    }

    private void loginUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        Log.d(TAG, "Attempting login with email: " + email);

        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Email is required");
            return;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Password is required");
            return;
        }

        // Basic email validation
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.setError("Please enter a valid email");
            return;
        }

        showProgress(true);
        authManager.loginUser(email, password, this);
    }

    private void proceedToMainMenu() {
        Log.d(TAG, "Proceeding to MainMenuActivity");
        Intent intent = new Intent(this, MainMenuActivity.class);
        startActivity(intent);
        Log.d(TAG, "Started MainMenuActivity, finishing LoginActivity");
        finish();
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!show);
        emailEditText.setEnabled(!show);
        passwordEditText.setEnabled(!show);
    }

    // RestAuthManager.AuthListener implementation
    @Override
    public void onLoginSuccess(String userId) {
        Log.d(TAG, "Login successful for userId: " + userId);
        showProgress(false);
        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();

        // Add a small delay to ensure SharedPreferences are written
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "Proceeding to main menu after successful login");
            proceedToMainMenu();
        }, 100);
    }

    @Override
    public void onLoginFailed(String error) {
        Log.e(TAG, "Login failed: " + error);
        showProgress(false);
        Toast.makeText(this, "Login failed: " + error, Toast.LENGTH_LONG).show();
    }

}