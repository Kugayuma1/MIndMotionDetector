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
        ClappingDetector.ClappingListener {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    // UI Components
    private PreviewView previewView;
    private TextView statusText;
    private TextView motionTypeText;
    private TextView instructionText;
    private TextView clapCounter;
    private TextView resultText;
    private LinearLayout statusBar;
    private LinearLayout progressContainer;
    private ImageView poseOverlay;

    // Camera and ML
    private ProcessCameraProvider cameraProvider;
    private PoseLandmarker poseLandmarker;
    private ExecutorService cameraExecutor;

    // Motion Detection
    private FirebaseRestManager firebaseManager;
    private ClappingDetector clappingDetector;
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
        instructionText = findViewById(R.id.instruction_text);
        clapCounter = findViewById(R.id.clap_counter);
        resultText = findViewById(R.id.result_text);
        statusBar = findViewById(R.id.status_bar);
        progressContainer = findViewById(R.id.progress_container);
        poseOverlay = findViewById(R.id.pose_overlay);
    }

    private void initializeComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize Firebase REST Manager
        firebaseManager = new FirebaseRestManager();
        firebaseManager.setListener(this);

        // Initialize Clapping Detector
        clappingDetector = new ClappingDetector();
        clappingDetector.setListener(this);

        // Start polling for sessions
        firebaseManager.startPollingForSessions();

        updateUI("Searching for motion sessions...", "", false, false);
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
        if (clappingDetector.isActive()) {
            clappingDetector.analyzePoseResult(result);
        }
    }

    private void onPoseDetectionError(RuntimeException error) {
        Log.e(TAG, "Pose detection error", error);
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
                clappingDetector.stopDetection();
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

    // Clapping Detection Listener Methods
    @Override
    public void onClappingDetected(int clapCount) {
        runOnUiThread(() -> {
            updateClapCounter(clapCount, clappingDetector.getRequiredClapCount());
        });
    }

    @Override
    public void onClappingCompleted() {
        runOnUiThread(() -> {
            updateUI("Motion detected successfully!", currentMotionType, false, true);

            // Notify Firebase that motion was detected
            if (currentSessionId != null) {
                firebaseManager.markMotionDetected(currentSessionId);
            }

            // Reset after a delay
            resultText.postDelayed(() -> {
                resetSession();
                updateUI("Searching for motion sessions...", "", false, false);
            }, 3000);
        });
    }

    @Override
    public void onClappingProgress(int currentClaps, int requiredClaps) {
        runOnUiThread(() -> {
            updateClapCounter(currentClaps, requiredClaps);
        });
    }

    @Override
    public void onDetectionTimeout() {
        runOnUiThread(() -> {
            updateUI("Motion detection timed out", currentMotionType, false, false);

            // Reset after a delay
            resultText.postDelayed(() -> {
                resetSession();
                updateUI("Searching for motion sessions...", "", false, false);
            }, 2000);
        });
    }

    private void startClappingDetection() {
        updateUI("Get ready to clap!", currentMotionType, true, false);
        clappingDetector.startDetection();
    }

    private void resetSession() {
        currentSessionId = null;
        currentMotionType = null;
        clappingDetector.stopDetection();
    }

    private void updateUI(String status, String motionType, boolean showProgress, boolean showResult) {
        statusText.setText(status);

        if (motionType.isEmpty()) {
            motionTypeText.setVisibility(TextView.GONE);
        } else {
            motionTypeText.setText("Motion: " + motionType);
            motionTypeText.setVisibility(TextView.VISIBLE);
        }

        progressContainer.setVisibility(showProgress ? LinearLayout.VISIBLE : LinearLayout.GONE);

        if (showResult) {
            resultText.setText(status);
            resultText.setVisibility(TextView.VISIBLE);
            resultText.setTextColor(getColor(R.color.result_background));
        } else {
            resultText.setVisibility(TextView.GONE);
        }
    }

    private void updateClapCounter(int current, int required) {
        clapCounter.setText(current + " / " + required + " claps");

        if (current == 0) {
            instructionText.setText("Start Clapping!");
        } else if (current < required) {
            instructionText.setText("Keep going!");
        }
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
}