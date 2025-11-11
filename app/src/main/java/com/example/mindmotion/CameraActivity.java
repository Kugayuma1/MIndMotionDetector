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
        JumpingDetector.JumpingListener,
        JumpingDetector.DebugListener,
        RestAuthManager.TokenRefreshListener,
        SpeechRecognitionManager.SpeechListener {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };
    private static final int MAX_AUTH_RETRIES = 3;

    // UI Components
    private TextView statusText, motionTypeText, clapCounter, resultText;
    private TextView debugPoseStatus, debugWristDistance, debugFingerDistance, debugClapStatus;
    private TextView speechStatusText, lastWordText;

    // Core Components
    private ProcessCameraProvider cameraProvider;
    private PoseLandmarker poseLandmarker;
    private ExecutorService cameraExecutor;
    private FirebaseRestManager firebaseManager;
    private ClappingDetector clappingDetector;
    private WavingDetector wavingDetector;
    private JumpingDetector jumpingDetector;
    private RestAuthManager authManager;
    private SpeechRecognitionManager speechManager;

    // Session State
    private String currentSessionId, currentMotionType;
    private int authRetryCount = 0;
    private boolean isHandlingAuthError = false;

    // Token refresh
    private Handler tokenRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable tokenRefreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initializeViews();
        initializeComponents();

        if (allPermissionsGranted()) {
            initializeCamera();
            initializeMediaPipe();
            initializeSpeechRecognition();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        authRetryCount = 0;
        isHandlingAuthError = false;

        startPeriodicTokenRefresh();
        if (firebaseManager != null) firebaseManager.onAppResume();
        if (speechManager != null) speechManager.resumeListening();
        validateAuthenticationAndStartPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPeriodicTokenRefresh();
        if (firebaseManager != null) firebaseManager.stopPolling();
        if (speechManager != null) speechManager.pauseListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPeriodicTokenRefresh();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (poseLandmarker != null) poseLandmarker.close();
        if (firebaseManager != null) firebaseManager.cleanup();
        if (authManager != null) authManager.cleanup();
        if (speechManager != null) speechManager.cleanup();
    }

    private void initializeViews() {
        statusText = findViewById(R.id.status_text);
        motionTypeText = findViewById(R.id.motion_type_text);
        clapCounter = findViewById(R.id.clap_counter);
        resultText = findViewById(R.id.result_text);
        debugPoseStatus = findViewById(R.id.debug_pose_status);
        debugWristDistance = findViewById(R.id.debug_wrist_distance);
        debugFingerDistance = findViewById(R.id.debug_finger_distance);
        debugClapStatus = findViewById(R.id.debug_clap_status);
        speechStatusText = findViewById(R.id.speech_status_text);
        lastWordText = findViewById(R.id.last_word_text);
    }

    private void initializeComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        authManager = new RestAuthManager(this);

        tokenRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (authManager != null && authManager.isUserLoggedIn()) {
                    authManager.refreshTokenIfNeeded(CameraActivity.this);
                }
                tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 30 * 60 * 1000);
            }
        };

        firebaseManager = new FirebaseRestManager(this);
        firebaseManager.setListener(this);

        clappingDetector = new ClappingDetector();
        clappingDetector.setListener(this);
        clappingDetector.setDebugListener(this);

        wavingDetector = new WavingDetector();
        wavingDetector.setListener(this);
        wavingDetector.setDebugListener(this);

        jumpingDetector = new JumpingDetector();
        jumpingDetector.setListener(this);
        jumpingDetector.setDebugListener(this);

        speechManager = new SpeechRecognitionManager(this);
        speechManager.setListener(this);

        updateUI("Initializing...", "", false, false);
        updateSpeechUI("Speech recognition initializing...", "");
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

    private void initializeSpeechRecognition() {
        if (speechManager.hasPermission()) {
            speechManager.startListening();
        } else {
            updateSpeechUI("Microphone permission required", "");
        }
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(((PreviewView) findViewById(R.id.preview_view)).getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
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
            MPImage mpImage = new BitmapImageBuilder(imageProxy.toBitmap()).build();
            poseLandmarker.detectAsync(mpImage, System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Error during pose detection", e);
        } finally {
            imageProxy.close();
        }
    }

    private void onPoseDetectionResult(PoseLandmarkerResult result, MPImage image) {
        if ("clapping".equals(currentMotionType) && clappingDetector.isActive()) {
            clappingDetector.analyzePoseResult(result);
        } else if ("wave".equals(currentMotionType) && wavingDetector.isActive()) {
            wavingDetector.analyzePoseResult(result);
        } else if ("jump".equals(currentMotionType) && jumpingDetector.isActive()) {
            jumpingDetector.analyzePoseResult(result);
        }
    }

    private void onPoseDetectionError(RuntimeException error) {
        runOnUiThread(() -> debugPoseStatus.setText("Pose: Error - " + error.getMessage()));
    }

    // Token refresh management
    private void startPeriodicTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, 5 * 60 * 1000);
    }

    private void stopPeriodicTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable);
    }

    @Override
    public void onTokenRefreshSuccess() {
        if (firebaseManager != null) firebaseManager.reloadTokensFromPreferences();
    }

    @Override
    public void onTokenRefreshFailed(String error) {
        if (error.contains("Session expired") || error.contains("No refresh token")) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Session expired. Returning to login.", Toast.LENGTH_LONG).show();
                authManager.logout();
                redirectToLogin();
            });
        }
    }

    private void validateAuthenticationAndStartPolling() {
        if (authManager == null || !authManager.isUserLoggedIn()) {
            redirectToLogin();
            return;
        }

        updateUI("Validating session and searching for motions...", "", false, false);
        makeAuthenticatedFirebaseCall();
    }

    private void makeAuthenticatedFirebaseCall() {
        authManager.getValidToken(new RestAuthManager.TokenRefreshListener() {
            @Override
            public void onTokenRefreshSuccess() {
                if (firebaseManager != null) {
                    firebaseManager.reloadTokensFromPreferences();
                    firebaseManager.startPollingForSessions();
                }
            }

            @Override
            public void onTokenRefreshFailed(String error) {
                runOnUiThread(() -> {
                    updateUI("Authentication error: " + error, "", false, false);
                    if (error.contains("Session expired")) redirectToLogin();
                });
            }
        });
    }

    private void redirectToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // Firebase REST Manager Listener Methods
    @Override
    public void onNewSessionFound(String sessionId, String motionType, String studentId) {
        runOnUiThread(() -> {
            currentSessionId = sessionId;
            currentMotionType = motionType;

            if ("clapping".equals(motionType)) {
                startClappingDetection();
            } else if ("wave".equals(motionType)) {
                startWavingDetection();
            } else if ("jump".equals(motionType)) {
                startJumpingDetection();
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
                resetSession();
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            if (error.contains("Authentication") || error.contains("expired") ||
                    error.contains("login") || error.contains("token")) {
                handleAuthError(error);
            } else {
                updateUI("Error: " + error, "", false, false);
                authRetryCount = 0;
            }
        });
    }

    private void handleAuthError(String error) {
        if (isHandlingAuthError) return;

        isHandlingAuthError = true;
        authRetryCount++;

        if (authRetryCount > MAX_AUTH_RETRIES) {
            Toast.makeText(this, "Authentication failed. Please login again.", Toast.LENGTH_LONG).show();
            redirectToLogin();
            return;
        }

        updateUI("Refreshing authentication... (Attempt " + authRetryCount + ")", "", false, false);

        authManager.getValidToken(new RestAuthManager.TokenRefreshListener() {
            @Override
            public void onTokenRefreshSuccess() {
                isHandlingAuthError = false;
                if (firebaseManager != null) {
                    firebaseManager.reloadTokensFromPreferences();
                    firebaseManager.startPollingForSessions();
                }
            }

            @Override
            public void onTokenRefreshFailed(String refreshError) {
                isHandlingAuthError = false;
                if (refreshError.contains("Session expired")) {
                    redirectToLogin();
                } else {
                    updateUI("Connection error, retrying...", "", false, false);
                }
            }
        });
    }

    @Override
    public void onMotionMarked(String sessionId) {
        // Motion successfully marked
    }

    @Override
    public void onVoiceDataSaved(String date, String data) {
        runOnUiThread(() -> Toast.makeText(this, "Voice data saved: " + data, Toast.LENGTH_SHORT).show());
    }

    // Clapping Detection Listener Methods
    @Override
    public void onClappingDetected(int clapCount) {
        runOnUiThread(() -> updateClapCounter(clapCount, clappingDetector.getRequiredClapCount(), "claps"));
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
        runOnUiThread(() -> updateClapCounter(currentClaps, requiredClaps, "claps"));
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
        runOnUiThread(() -> updateClapCounter(waveCount, wavingDetector.getRequiredWaveCount(), "waves"));
    }

    public void onWavingCompleted() {
        runOnUiThread(() -> {
            updateUI("Motion detected successfully! 🌊", currentMotionType, false, true);
            onMotionCompleted();
        });
    }

    public void onWavingProgress(int currentWaves, int requiredWaves) {
        runOnUiThread(() -> updateClapCounter(currentWaves, requiredWaves, "waves"));
    }

    // Jumping Detection Listener Methods
    public void onJumpingDetected(int jumpCount) {
        runOnUiThread(() -> updateClapCounter(jumpCount, jumpingDetector.getRequiredJumpCount(), "jumps"));
    }

    public void onJumpingCompleted() {
        runOnUiThread(() -> {
            updateUI("Motion detected successfully! 🦘", currentMotionType, false, true);
            onMotionCompleted();
        });
    }

    public void onJumpingProgress(int currentJumps, int requiredJumps) {
        runOnUiThread(() -> updateClapCounter(currentJumps, requiredJumps, "jumps"));
    }

    // Speech Recognition Listener Methods
    @Override
    public void onWordDetected(String word) {
        runOnUiThread(() -> {
            updateSpeechUI("Listening...", "Last word: " + word);
            if (firebaseManager != null) {
                firebaseManager.saveVoiceData(word);
            }
        });
    }

    @Override
    public void onSpeechError(String error) {
        runOnUiThread(() -> updateSpeechUI("Speech error: " + error, ""));
    }

    @Override
    public void onSpeechStatusChanged(boolean isListening) {
        runOnUiThread(() -> {
            String status = isListening ? "Listening for words..." : "Speech recognition paused";
            updateSpeechUI(status, lastWordText.getText().toString());
        });
    }

    // Debug Listener Methods - SEPARATED LIKE YOUR ORIGINAL CODE

    // ClappingDetector.DebugListener implementation
    public void onClapDebugUpdate(String poseStatus, String wristDistance, String fingerDistance, String clapStatus) {
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: " + poseStatus);
            debugWristDistance.setText("Wrist distance: " + wristDistance);
            debugFingerDistance.setText("Finger distance: " + fingerDistance);
            debugClapStatus.setText("Clap detection: " + clapStatus);
        });
    }

    // WavingDetector.DebugListener implementation
    public void onWaveDebugUpdate(String poseStatus, String handsHeight, String waveMovement, String waveStatus) {
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: " + poseStatus);
            debugWristDistance.setText("Hands height: " + handsHeight);
            debugFingerDistance.setText("Wave movement: " + waveMovement);
            debugClapStatus.setText("Wave detection: " + waveStatus);
        });
    }

    // JumpingDetector.DebugListener implementation
    public void onJumpDebugUpdate(String poseStatus, String bodyHeight, String feetStatus, String jumpStatus) {
        runOnUiThread(() -> {
            debugPoseStatus.setText("Pose: " + poseStatus);
            debugWristDistance.setText("Body height: " + bodyHeight);
            debugFingerDistance.setText("Feet status: " + feetStatus);
            debugClapStatus.setText("Jump detection: " + jumpStatus);
        });
    }

    private void updateSpeechUI(String status, String lastWord) {
        if (speechStatusText != null) {
            speechStatusText.setText(status);
        }
        if (lastWordText != null && !lastWord.isEmpty()) {
            lastWordText.setText(lastWord);
        }
    }

    // Motion Detection Control
    private void startClappingDetection() {
        updateUI("Clapping detection active", currentMotionType, true, false);
        wavingDetector.stopDetection();
        jumpingDetector.stopDetection();
        clappingDetector.startDetection();
    }

    private void startWavingDetection() {
        updateUI("Waving detection active", currentMotionType, true, false);
        clappingDetector.stopDetection();
        jumpingDetector.stopDetection();
        wavingDetector.startDetection();
    }

    private void startJumpingDetection() {
        updateUI("Jumping detection active", currentMotionType, true, false);
        clappingDetector.stopDetection();
        wavingDetector.stopDetection();
        jumpingDetector.startDetection();
    }

    private void onMotionCompleted() {
        if (currentSessionId != null) firebaseManager.markMotionDetected(currentSessionId);

        resultText.postDelayed(() -> {
            resetSession();
            updateUI("Searching for motion sessions...", "", false, false);
        }, 3000);
    }

    private void onMotionTimedOut() {
        resultText.postDelayed(() -> {
            resetSession();
            updateUI("Searching for motion sessions...", "", false, false);
        }, 2000);
    }

    private void resetSession() {
        currentSessionId = null;
        currentMotionType = null;
        clappingDetector.stopDetection();
        wavingDetector.stopDetection();
        jumpingDetector.stopDetection();
    }

    private void updateUI(String status, String motionType, boolean showCounter, boolean showResult) {
        statusText.setText(status);

        motionTypeText.setVisibility(motionType.isEmpty() ? TextView.GONE : TextView.VISIBLE);
        if (!motionType.isEmpty()) motionTypeText.setText("Motion: " + motionType);

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
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initializeCamera();
                initializeMediaPipe();
                initializeSpeechRecognition();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}