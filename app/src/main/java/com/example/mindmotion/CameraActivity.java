package com.example.mindmotion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity
        implements FirebaseRestManager.SessionPollerListener,
        ClappingDetector.ClappingListener,
        ClappingDetector.DebugListener,
        WavingDetector.WavingListener,
        WavingDetector.DebugListener,
        RestAuthManager.TokenRefreshListener {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    // UI Components
    private PreviewView previewView;
    private TextView statusText;
    private TextView motionTypeText;
    private TextView clapCounter;
    private TextView resultText;
    private LinearLayout statusBar;
    private ImageView poseOverlay;

    // Debug UI Components
    private TextView debugPoseStatus;
    private TextView debugWristDistance;
    private TextView debugFingerDistance;
    private TextView debugClapStatus;
    private LinearLayout debugPanel;

    // Camera and ML
    private ProcessCameraProvider cameraProvider;
    private PoseLandmarker poseLandmarker;
    private ExecutorService cameraExecutor;

    // Motion Detection
    private FirebaseRestManager firebaseManager;
    private ClappingDetector clappingDetector;
    private WavingDetector wavingDetector;
    private String currentSessionId;
    private String currentMotionType;

    private int authRetryCount = 0;
    private static final int MAX_AUTH_RETRIES = 3;
    private boolean isHandlingAuthError = false;

    // Auth manager for token validation
    private RestAuthManager authManager;

    // Token refresh components
    private Handler tokenRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable tokenRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Performing periodic token refresh check in camera");
            if (authManager != null && authManager.isUserLoggedIn()) {
                authManager.refreshTokenIfNeeded(CameraActivity.this);
            }
            // Schedule next refresh in 30 minutes
            tokenRefreshHandler.postDelayed(this, 30 * 60 * 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initializeViews();
        initializeComponents();

        if (allPermissionsGranted()) {
            initializeCamera();
            initializeMediaPipe();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "CameraActivity onResume - starting token refresh and validation");

        // Reset retry counter when activity resumes
        authRetryCount = 0;
        isHandlingAuthError = false;

        // Start periodic token refresh
        startPeriodicTokenRefresh();

        // Let FirebaseManager know app resumed
        if (firebaseManager != null) {
            firebaseManager.onAppResume();
        }

        // Check authentication when activity resumes
        validateAuthenticationAndStartPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "CameraActivity onPause - stopping token refresh and polling");

        // Stop periodic token refresh
        stopPeriodicTokenRefresh();

        // Stop polling when activity is not visible to conserve resources
        if (firebaseManager != null) {
            firebaseManager.stopPolling();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "CameraActivity onDestroy - cleaning up");

        // Stop token refresh
        stopPeriodicTokenRefresh();

        // Existing cleanup code
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        if (firebaseManager != null) {
            firebaseManager.cleanup();
        }
        if (authManager != null) {
            authManager.cleanup();
        }
    }

    // Token refresh management methods
    private void startPeriodicTokenRefresh() {
        Log.d(TAG, "Starting periodic token refresh in camera activity");
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
        // Start checking after 5 minutes, then every 30 minutes
        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 5 * 60 * 1000);
    }

    private void stopPeriodicTokenRefresh() {
        Log.d(TAG, "Stopping periodic token refresh in camera activity");
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
    }

    // Token Refresh Listener implementation
    @Override
    public void onTokenRefreshSuccess() {
        Log.d(TAG, "Periodic token refresh successful in camera activity");
        // Reload tokens to FirebaseManager to ensure it has the latest tokens
        loadLatestTokensToFirebaseManager();
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        Log.e(TAG, "Periodic token refresh failed in camera: " + error);

        if (error.contains("Session expired") || error.contains("No refresh token")) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Session expired. Returning to login.", Toast.LENGTH_LONG).show();

                authManager.logout();
                redirectToLogin();
            });
        }
        // For network errors, just log and try again next cycle
    }

    // Enhanced method to make authenticated API calls
    private void makeAuthenticatedFirebaseCall() {
        if (authManager == null) {
            Log.e(TAG, "AuthManager not available for authenticated call");
            return;
        }

        authManager.getValidToken(new RestAuthManager.TokenRefreshListener() {
            @Override
            public void onTokenRefreshSuccess() {
                // Token is valid, proceed with Firebase operations
                String validToken = authManager.getIdToken();
                Log.d(TAG, "Using valid token for Firebase operations");

                // Ensure FirebaseManager has the latest token
                loadLatestTokensToFirebaseManager();

                // Now start polling with valid token
                if (firebaseManager != null) {
                    firebaseManager.startPollingForSessions();
                }
            }

            @Override
            public void onTokenRefreshFailed(String error) {
                Log.e(TAG, "Cannot get valid token for Firebase call: " + error);
                runOnUiThread(() -> {
                    updateUI("Authentication error: " + error, "", false, false);
                    if (error.contains("Session expired")) {
                        redirectToLogin();
                    }
                });
            }
        });
    }

    private void validateAuthenticationAndStartPolling() {
        if (authManager == null) {
            Log.e(TAG, "AuthManager not initialized");
            return;
        }

        // Check if user is still logged in
        if (!authManager.isUserLoggedIn()) {
            Log.w(TAG, "User is no longer logged in, returning to login screen");
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_LONG).show();
            redirectToLogin();
            return;
        }

        updateUI("Validating session and searching for motions...", "", false, false);

        // Use the authenticated call method instead of direct polling
        makeAuthenticatedFirebaseCall();
    }

    private void loadLatestTokensToFirebaseManager() {
        // Force FirebaseRestManager to reload tokens from SharedPreferences
        // This ensures it has the most up-to-date tokens
        if (firebaseManager != null) {
            firebaseManager.reloadTokensFromPreferences();
        }
    }

    private void redirectToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void initializeViews() {
        previewView = findViewById(R.id.preview_view);
        statusText = findViewById(R.id.status_text);
        motionTypeText = findViewById(R.id.motion_type_text);
        clapCounter = findViewById(R.id.clap_counter);
        resultText = findViewById(R.id.result_text);
        statusBar = findViewById(R.id.status_bar);
        poseOverlay = findViewById(R.id.pose_overlay);

        // Debug components
        debugPoseStatus = findViewById(R.id.debug_pose_status);
        debugWristDistance = findViewById(R.id.debug_wrist_distance);
        debugFingerDistance = findViewById(R.id.debug_finger_distance);
        debugClapStatus = findViewById(R.id.debug_clap_status);
        debugPanel = findViewById(R.id.debug_panel);
    }

    private void initializeComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize auth manager first
        authManager = new RestAuthManager(this);

        // Initialize Firebase REST Manager with context
        firebaseManager = new FirebaseRestManager(this);
        firebaseManager.setListener(this);

        // Initialize both detectors
        clappingDetector = new ClappingDetector();
        clappingDetector.setListener(this);
        clappingDetector.setDebugListener(this);

        wavingDetector = new WavingDetector();
        wavingDetector.setListener(this);
        wavingDetector.setDebugListener(this);

        updateUI("Initializing...", "", false, false);

        // Don't start polling immediately - wait for onResume to validate auth first
    }

    private void initializeMediaPipe() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_heavy.task")
                    .build();

            PoseLandmarkerOptions options = PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::onPoseDetectionResult)
                    .setErrorListener(this::onPoseDetectionError)
                    .build();

            poseLandmarker = PoseLandmarker.createFromOptions(this, options);
            Log.d(TAG, "MediaPipe initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe", e);
            Toast.makeText(this, "Failed to initialize pose detection", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // Preview use case
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image analysis use case
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        // Camera selector
        CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll();

            // Bind use cases to camera
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (poseLandmarker == null) {
            imageProxy.close();
            return;
        }

        try {
            // Convert ImageProxy to MPImage
            MPImage mpImage = new BitmapImageBuilder(imageProxy.toBitmap()).build();

            // Detect pose landmarks
            long frameTimeMs = System.currentTimeMillis();
            poseLandmarker.detectAsync(mpImage, frameTimeMs);

        } catch (Exception e) {
            Log.e(TAG, "Error during pose detection", e);
        } finally {
            imageProxy.close();
        }
    }

    private void onPoseDetectionResult(PoseLandmarkerResult result, MPImage image) {
        // Route to active detector based on current motion type
        if ("clapping".equals(currentMotionType) && clappingDetector.isActive()) {
            clappingDetector.analyzePoseResult(result);
        } else if ("wave".equals(currentMotionType) && wavingDetector.isActive()) {
            wavingDetector.analyzePoseResult(result);
        }
    }

    private void onPoseDetectionError(RuntimeException error) {
        Log.e(TAG, "Pose detection error", error);
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: Error - " + error.getMessage());
        });
    }

    // Firebase REST Manager Listener Methods
    @Override
    public void onNewSessionFound(String sessionId, String motionType, String studentId) {
        runOnUiThread(() -> {
            Log.d(TAG, "New session found: " + sessionId + ", motion: " + motionType);
            currentSessionId = sessionId;
            currentMotionType = motionType;

            if ("clapping".equals(motionType)) {
                startClappingDetection();
            } else if ("wave".equals(motionType)) {
                startWavingDetection();
            } else {
                updateUI("Unknown motion type: " + motionType, motionType, false, false);
            }
        });
    }

    @Override
    public void onSessionTimedOut(String sessionId) {
        runOnUiThread(() -> {
            if (sessionId.equals(currentSessionId)) {
                updateUI("Session timed out", "", false, false);
                stopAllDetectors();
                resetSession();
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            Log.e(TAG, "Firebase error: " + error);

            // Check if it's an authentication error
            if (error.contains("Authentication") || error.contains("expired") ||
                    error.contains("login") || error.contains("token")) {

                // Prevent multiple simultaneous auth error handlers
                if (isHandlingAuthError) {
                    Log.d(TAG, "Already handling auth error, skipping");
                    return;
                }

                isHandlingAuthError = true;
                authRetryCount++;

                // If we've tried too many times, force re-login
                if (authRetryCount > MAX_AUTH_RETRIES) {
                    Log.e(TAG, "Max auth retries exceeded, forcing re-login");
                    Toast.makeText(this, "Authentication failed. Please login again.",
                            Toast.LENGTH_LONG).show();
                    authRetryCount = 0;
                    isHandlingAuthError = false;
                    redirectToLogin();
                    return;
                }

                updateUI("Refreshing authentication... (Attempt " + authRetryCount + ")", "", false, false);
                Toast.makeText(this, "Refreshing session, please wait...", Toast.LENGTH_SHORT).show();

                // Try to refresh token and restart operations
                if (authManager != null) {
                    authManager.getValidToken(new RestAuthManager.TokenRefreshListener() {
                        @Override
                        public void onTokenRefreshSuccess() {
                            Log.d(TAG, "Token refresh successful, restarting operations");
                            isHandlingAuthError = false;
                            loadLatestTokensToFirebaseManager();

                            // Restart polling
                            if (firebaseManager != null) {
                                firebaseManager.startPollingForSessions();
                            }
                        }

                        @Override
                        public void onTokenRefreshFailed(String refreshError) {
                            Log.e(TAG, "Token refresh failed during error recovery: " + refreshError);
                            isHandlingAuthError = false;

                            if (refreshError.contains("Session expired")) {
                                redirectToLogin();
                            } else {
                                // Network error, try again later
                                updateUI("Connection error, retrying...", "", false, false);
                            }
                        }
                    });
                }
            } else {
                // Non-auth error, just display it
                updateUI("Error: " + error, "", false, false);
                authRetryCount = 0; // Reset counter on non-auth errors
            }
        });
    }

    @Override
    public void onMotionMarked(String sessionId) {
        Log.d(TAG, "Motion successfully marked for session: " + sessionId);
    }

    @Override
    public void onVoiceDataSaved(String date, String data) {
        Log.d(TAG, "Voice data saved for date: " + date + ", data: " + data);
        runOnUiThread(() -> {
            Toast.makeText(this, "Voice data saved: " + data, Toast.LENGTH_SHORT).show();
        });
    }

    // Clapping Detection Listener Methods
    @Override
    public void onClappingDetected(int clapCount) {
        runOnUiThread(() -> {
            updateClapCounter(clapCount, clappingDetector.getRequiredClapCount(), "claps");
        });
    }

    @Override
    public void onClappingCompleted() {
        runOnUiThread(() -> {
            updateUI("Motion detected successfully! 🎉", currentMotionType, false, true);
            onMotionCompleted();
        });
    }

    @Override
    public void onClappingProgress(int currentClaps, int requiredClaps) {
        runOnUiThread(() -> {
            updateClapCounter(currentClaps, requiredClaps, "claps");
        });
    }

    @Override
    public void onDetectionTimeout() {
        runOnUiThread(() -> {
            updateUI("Motion detection timed out", currentMotionType, false, false);
            onMotionTimedOut();
        });
    }

    // Waving Detection Listener Methods
    public void onWavingDetected(int waveCount) {
        runOnUiThread(() -> {
            updateClapCounter(waveCount, wavingDetector.getRequiredWaveCount(), "waves");
        });
    }

    public void onWavingCompleted() {
        runOnUiThread(() -> {
            updateUI("Motion detected successfully! 🌊", currentMotionType, false, true);
            onMotionCompleted();
        });
    }

    public void onWavingProgress(int currentWaves, int requiredWaves) {
        runOnUiThread(() -> {
            updateClapCounter(currentWaves, requiredWaves, "waves");
        });
    }

    // Clapping Debug Listener Methods
    public void onClapDebugUpdate(String poseStatus, String wristDistance, String fingerDistance, String clapStatus) {
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: " + poseStatus);
            debugWristDistance.setText("Wrist distance: " + wristDistance);
            debugFingerDistance.setText("Finger distance: " + fingerDistance);
            debugClapStatus.setText("Clap detection: " + clapStatus);
        });
    }

    // Waving Debug Listener Methods (WavingDetector.DebugListener)
    public void onWaveDebugUpdate(String poseStatus, String handsHeight, String waveMovement, String waveStatus) {
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: " + poseStatus);
            debugWristDistance.setText("Hands height: " + handsHeight);
            debugFingerDistance.setText("Wave movement: " + waveMovement);
            debugClapStatus.setText("Wave detection: " + waveStatus);
        });
    }

    private void startClappingDetection() {
        updateUI("Clapping detection active", currentMotionType, true, false);
        wavingDetector.stopDetection(); // Ensure only one detector is active
        clappingDetector.startDetection();
    }

    private void startWavingDetection() {
        updateUI("Waving detection active", currentMotionType, true, false);
        clappingDetector.stopDetection(); // Ensure only one detector is active
        wavingDetector.startDetection();
    }

    private void stopAllDetectors() {
        clappingDetector.stopDetection();
        wavingDetector.stopDetection();
    }

    private void onMotionCompleted() {
        // Notify Firebase that motion was detected
        if (currentSessionId != null) {
            firebaseManager.markMotionDetected(currentSessionId);
        }

        // Reset after a delay
        resultText.postDelayed(() -> {
            resetSession();
            updateUI("Searching for motion sessions...", "", false, false);
        }, 3000);
    }

    private void onMotionTimedOut() {
        // Reset after a delay
        resultText.postDelayed(() -> {
            resetSession();
            updateUI("Searching for motion sessions...", "", false, false);
        }, 2000);
    }

    private void resetSession() {
        currentSessionId = null;
        currentMotionType = null;
        stopAllDetectors();
    }

    private void updateUI(String status, String motionType, boolean showCounter, boolean showResult) {
        statusText.setText(status);

        if (motionType.isEmpty()) {
            motionTypeText.setVisibility(TextView.GONE);
        } else {
            motionTypeText.setText("Motion: " + motionType);
            motionTypeText.setVisibility(TextView.VISIBLE);
        }

        // Show/hide motion counter based on detection state
        clapCounter.setVisibility(showCounter ? TextView.VISIBLE : TextView.GONE);

        if (showResult) {
            resultText.setText(status);
            resultText.setVisibility(TextView.VISIBLE);
            resultText.setTextColor(getColor(R.color.result_background));
        } else {
            resultText.setVisibility(TextView.GONE);
        }
    }

    private void updateClapCounter(int current, int required, String motionName) {
        clapCounter.setText(current + " / " + required + " " + motionName + " detected");
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initializeCamera();
                initializeMediaPipe();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void saveDetectedSpeech(String spokenText) {
        if (firebaseManager != null) {
            firebaseManager.saveVoiceData(spokenText);
        }
    }
}