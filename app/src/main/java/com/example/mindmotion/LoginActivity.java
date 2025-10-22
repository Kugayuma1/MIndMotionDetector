package com.example.mindmotion;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity implements RestAuthManager.AuthListener {
    private EditText emailEditText;
    private EditText passwordEditText;
    private ImageButton loginButton;
    private ImageButton togglePasswordButton;
    private ProgressBar progressBar;
    private boolean isPasswordVisible = false;

    private RestAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        authManager = new RestAuthManager(this);

        if (authManager.isUserLoggedIn()) {
            proceedToMainMenu();
            return;
        }

        initializeViews();
    }

    private void initializeViews() {
        emailEditText = findViewById(R.id.et_email);
        passwordEditText = findViewById(R.id.et_password);
        loginButton = findViewById(R.id.btn_login);
        togglePasswordButton = findViewById(R.id.btn_toggle_password);
        progressBar = findViewById(R.id.progress_bar);

        loginButton.setOnClickListener(v -> loginUser());
        togglePasswordButton.setOnClickListener(v -> togglePasswordVisibility());
    }

    private void togglePasswordVisibility() {
        if (isPasswordVisible) {
            // Hide password
            passwordEditText.setTransformationMethod(PasswordTransformationMethod.getInstance());
            togglePasswordButton.setImageResource(R.drawable.eyeofthetiger);
            isPasswordVisible = false;
        } else {
            // Show password
            passwordEditText.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            togglePasswordButton.setImageResource(R.drawable.eye);
            isPasswordVisible = true;
        }

        // Move cursor to end of text
        passwordEditText.setSelection(passwordEditText.getText().length());
    }

    private void loginUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Email is required");
            return;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Password is required");
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.setError("Please enter a valid email");
            return;
        }

        showProgress(true);
        authManager.loginUser(email, password, this);
    }

    private void proceedToMainMenu() {
        startActivity(new Intent(this, MainMenuActivity.class));
        finish();
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!show);
        emailEditText.setEnabled(!show);
        passwordEditText.setEnabled(!show);
    }

    @Override
    public void onLoginSuccess(String userId) {
        showProgress(false);
        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
        proceedToMainMenu();
    }

    @Override
    public void onLoginFailed(String error) {
        showProgress(false);
        Toast.makeText(this, "Login failed: " + error, Toast.LENGTH_LONG).show();
    }
}