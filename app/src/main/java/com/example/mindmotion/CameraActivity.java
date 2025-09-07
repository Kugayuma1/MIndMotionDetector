package com.example.mindmotion;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
        WavingDetector.DebugListener {

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

        // Start polling for sessions
        firebaseManager.startPollingForSessions();

        updateUI("Searching for your motion sessions...", "", false, false);
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
            updateUI("Error: " + error, "", false, false);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        if (firebaseManager != null) {
            firebaseManager.cleanup();
        }
    }

    private void saveDetectedSpeech(String spokenText) {
        if (firebaseManager != null) {
            firebaseManager.saveVoiceData(spokenText);
        }
    }
}