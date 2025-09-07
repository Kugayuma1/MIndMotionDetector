package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class WavingDetector {
    private static final String TAG = "WavingDetector";

    // Detection parameters for waving
    private static final double WAVE_HEIGHT_THRESHOLD = 0.15; // Minimum vertical movement for wave
    private static final double WAVE_HORIZONTAL_THRESHOLD = 0.1; // Minimum horizontal movement
    private static final int REQUIRED_WAVE_COUNT = 2; // Number of waves required (back and forth = 2 waves)
    private static final long WAVE_COOLDOWN_MS = 300; // Minimum time between wave detections
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30 seconds to complete waves
    private static final double HANDS_UP_THRESHOLD = 0.3; // Both hands must be above this relative to shoulders

    // Pose landmark indices (MediaPipe Pose)
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;
    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_ELBOW = 13;
    private static final int RIGHT_ELBOW = 14;

    // State tracking for wave detection
    private List<Long> waveTimes;
    private long lastWaveTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private WavingListener listener;
    private DebugListener debugListener;

    // Wave motion tracking
    private double leftWristLastX = 0.0;
    private double rightWristLastX = 0.0;
    private boolean leftHandMovingRight = true;
    private boolean rightHandMovingRight = false;
    private boolean lastHandsUp = false;

    // Debug tracking
    private boolean lastHandsVisible = false;
    private double lastLeftWristHeight = 0.0;
    private double lastRightWristHeight = 0.0;
    private boolean lastWaveState = false;

    public interface WavingListener {
        void onWavingDetected(int waveCount);
        void onWavingCompleted();
        void onWavingProgress(int currentWaves, int requiredWaves);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onWaveDebugUpdate(String poseStatus, String handsHeight, String waveMovement, String waveStatus);
    }

    public WavingDetector() {
        waveTimes = new ArrayList<>();
        reset();
    }

    public void setListener(WavingListener listener) {
        this.listener = listener;
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting waving detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onWavingProgress(0, REQUIRED_WAVE_COUNT);
        }

        updateDebugInfo();
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping waving detection");
        isDetectionActive = false;
        updateDebugInfo();
    }

    public void reset() {
        waveTimes.clear();
        lastWaveTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        leftWristLastX = 0.0;
        rightWristLastX = 0.0;
        leftHandMovingRight = true;
        rightHandMovingRight = false;
        lastHandsUp = false;
        lastHandsVisible = false;
        lastLeftWristHeight = 0.0;
        lastRightWristHeight = 0.0;
        lastWaveState = false;
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose detected", "N/A", "N/A", "Inactive");
            return;
        }

        // Check if detection has timed out
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Waving detection timed out");
            if (listener != null) {
                listener.onDetectionTimeout();
            }
            stopDetection();
            return;
        }

        // Get the first person's landmarks
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        if (landmarks.size() <= Math.max(RIGHT_SHOULDER, RIGHT_WRIST)) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Active - Waiting for pose");
            return;
        }

        // Get relevant landmarks
        NormalizedLandmark leftWrist = landmarks.get(LEFT_WRIST);
        NormalizedLandmark rightWrist = landmarks.get(RIGHT_WRIST);
        NormalizedLandmark leftShoulder = landmarks.get(LEFT_SHOULDER);
        NormalizedLandmark rightShoulder = landmarks.get(RIGHT_SHOULDER);

        // Check if hands are visible
        boolean handsVisible = isLandmarkVisible(leftWrist) && isLandmarkVisible(rightWrist) &&
                isLandmarkVisible(leftShoulder) && isLandmarkVisible(rightShoulder);

        lastHandsVisible = handsVisible;

        if (!handsVisible) {
            updateDebugInfo("Hands not visible", "N/A", "N/A", "Active - Show both hands up");
            return;
        }

        // Check if both hands are raised (above shoulders)
        double leftWristHeight = leftShoulder.y() - leftWrist.y(); // Positive = hand above shoulder
        double rightWristHeight = rightShoulder.y() - rightWrist.y();

        lastLeftWristHeight = leftWristHeight;
        lastRightWristHeight = rightWristHeight;

        boolean handsUp = leftWristHeight > HANDS_UP_THRESHOLD && rightWristHeight > HANDS_UP_THRESHOLD;
        lastHandsUp = handsUp;

        if (!handsUp) {
            updateDebugInfo("Hands visible",
                    String.format("L:%.3f R:%.3f (need >%.3f)", leftWristHeight, rightWristHeight, HANDS_UP_THRESHOLD),
                    "Hands not raised", "Active - Raise both hands");
            return;
        }

        // Detect waving motion (horizontal movement while hands are up)
        boolean waveDetected = detectWaveMotion(leftWrist, rightWrist);
        lastWaveState = waveDetected;

        if (waveDetected) {
            long currentTime = System.currentTimeMillis();

            // Check cooldown to avoid multiple detections of same wave
            if (currentTime - lastWaveTime > WAVE_COOLDOWN_MS) {
                registerWave(currentTime);
            }
        }

        // Update debug information
        String poseStatus = handsVisible ? "Both hands visible" : "Hands not visible";
        String handsHeightStr = String.format("L:%.3f R:%.3f (thresh:%.3f)",
                leftWristHeight, rightWristHeight, HANDS_UP_THRESHOLD);
        String movementStr = String.format("L-X:%.3f R-X:%.3f", leftWrist.x(), rightWrist.x());
        String waveStatus = String.format("Active - %s (%d/%d waves)",
                waveDetected ? "WAVING" : "Wave both hands",
                waveTimes.size(), REQUIRED_WAVE_COUNT);

        updateDebugInfo(poseStatus, handsHeightStr, movementStr, waveStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        // MediaPipe Tasks API uses visibility score
        return landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true;
    }

    private boolean detectWaveMotion(NormalizedLandmark leftWrist, NormalizedLandmark rightWrist) {
        double currentLeftX = leftWrist.x();
        double currentRightX = rightWrist.x();

        // Initialize if first detection
        if (leftWristLastX == 0.0) {
            leftWristLastX = currentLeftX;
            rightWristLastX = currentRightX;
            return false;
        }

        // Calculate horizontal movement
        double leftMovement = currentLeftX - leftWristLastX;
        double rightMovement = currentRightX - rightWristLastX;

        // Check for significant horizontal movement in opposite directions
        boolean leftMovedSignificantly = Math.abs(leftMovement) > WAVE_HORIZONTAL_THRESHOLD;
        boolean rightMovedSignificantly = Math.abs(rightMovement) > WAVE_HORIZONTAL_THRESHOLD;

        // For waving, we want coordinated movement - both hands moving outward or inward
        boolean isWaveMotion = false;

        if (leftMovedSignificantly && rightMovedSignificantly) {
            // Both hands moving outward (away from center)
            if (leftMovement < 0 && rightMovement > 0) {
                isWaveMotion = true;
            }
            // Both hands moving inward (toward center)
            else if (leftMovement > 0 && rightMovement < 0) {
                isWaveMotion = true;
            }
        }

        // Update last positions
        leftWristLastX = currentLeftX;
        rightWristLastX = currentRightX;

        return isWaveMotion;
    }

    private void registerWave(long currentTime) {
        waveTimes.add(currentTime);
        lastWaveTime = currentTime;

        Log.d(TAG, "Wave detected! Count: " + waveTimes.size() + "/" + REQUIRED_WAVE_COUNT);

        if (listener != null) {
            listener.onWavingDetected(waveTimes.size());
            listener.onWavingProgress(waveTimes.size(), REQUIRED_WAVE_COUNT);
        }

        // Check if we've reached the required number of waves
        if (waveTimes.size() >= REQUIRED_WAVE_COUNT) {
            Log.d(TAG, "Waving sequence completed!");
            if (listener != null) {
                listener.onWavingCompleted();
            }
            stopDetection();
        }
    }

    private void updateDebugInfo() {
        updateDebugInfo(
                lastHandsVisible ? "Both hands visible" : "Hands not visible",
                String.format("L:%.3f R:%.3f", lastLeftWristHeight, lastRightWristHeight),
                "N/A",
                isDetectionActive ? "Active" : "Inactive"
        );
    }

    private void updateDebugInfo(String poseStatus, String handsHeight, String waveMovement, String waveStatus) {
        if (debugListener != null) {
            debugListener.onWaveDebugUpdate(poseStatus, handsHeight, waveMovement, waveStatus);
        }
    }

    public boolean isActive() {
        return isDetectionActive;
    }

    public int getCurrentWaveCount() {
        return waveTimes.size();
    }

    public int getRequiredWaveCount() {
        return REQUIRED_WAVE_COUNT;
    }

    public long getRemainingTime() {
        if (!isDetectionActive) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}