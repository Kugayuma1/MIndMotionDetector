package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class WavingDetector {
    private static final String TAG = "WavingDetector";

    // Detection parameters for waving (made easier for seated users)
    private static final double WAVE_HORIZONTAL_THRESHOLD = 0.02; // Reduced horizontal movement needed
    private static final int REQUIRED_WAVE_COUNT = 3; // Number of wave motions required
    private static final long WAVE_COOLDOWN_MS = 100; // Time between wave detections
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30 seconds to complete waves
    private static final double HANDS_VISIBLE_THRESHOLD = 0.1; // Just need hands to be roughly at shoulder level or slightly above

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
    private int framesSinceLastWave = 0;
    private boolean expectingInwardMotion = false; // Track wave cycle

    // Debug tracking
    private boolean lastHandsVisible = false;
    private double lastLeftWristHeight = 0.0;
    private double lastRightWristHeight = 0.0;
    private String lastMovementDescription = "";

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
        framesSinceLastWave = 0;
        expectingInwardMotion = false;
        lastHandsVisible = false;
        lastLeftWristHeight = 0.0;
        lastRightWristHeight = 0.0;
        lastMovementDescription = "";
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
            updateDebugInfo("Hands not visible", "N/A", "N/A", "Active - Show both hands");
            return;
        }

        // Check if hands are at a reasonable height (much more lenient)
        // Hands just need to be roughly at shoulder level or slightly above/below
        double leftWristHeight = leftShoulder.y() - leftWrist.y();
        double rightWristHeight = rightShoulder.y() - rightWrist.y();

        lastLeftWristHeight = leftWristHeight;
        lastRightWristHeight = rightWristHeight;

        // Very lenient height check - hands can be slightly below shoulders too
        boolean handsAtReasonableHeight = leftWristHeight > -0.1 && rightWristHeight > -0.1;

        if (!handsAtReasonableHeight) {
            updateDebugInfo("Hands visible",
                    String.format("L:%.3f R:%.3f (need >-0.1)", leftWristHeight, rightWristHeight),
                    "Hands too low", "Active - Lift hands slightly");
            return;
        }

        // Detect waving motion (simplified)
        framesSinceLastWave++;
        boolean waveDetected = detectSimpleWaveMotion(leftWrist, rightWrist);

        if (waveDetected) {
            long currentTime = System.currentTimeMillis();

            // Check cooldown to avoid multiple detections of same wave
            if (currentTime - lastWaveTime > WAVE_COOLDOWN_MS) {
                registerWave(currentTime);
                framesSinceLastWave = 0;
            }
        }

        // Update debug information
        String poseStatus = handsVisible ? "Both hands visible" : "Hands not visible";
        String handsHeightStr = String.format("L:%.3f R:%.3f (need >-0.1)",
                leftWristHeight, rightWristHeight);
        String waveStatus = String.format("Active - %s (%d/%d waves)",
                waveDetected ? "WAVING" : "Wave hands side to side",
                waveTimes.size(), REQUIRED_WAVE_COUNT);

        updateDebugInfo(poseStatus, handsHeightStr, lastMovementDescription, waveStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        return landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true;
    }

    private boolean detectSimpleWaveMotion(NormalizedLandmark leftWrist, NormalizedLandmark rightWrist) {
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

        // Check for significant movement in either hand
        boolean leftMovedSignificantly = Math.abs(leftMovement) > WAVE_HORIZONTAL_THRESHOLD;
        boolean rightMovedSignificantly = Math.abs(rightMovement) > WAVE_HORIZONTAL_THRESHOLD;

        boolean isWaveMotion = false;
        String movementDesc = "";

        // Simplified wave detection - look for coordinated outward or inward movement
        if (leftMovedSignificantly || rightMovedSignificantly) {
            // Outward motion (hands moving away from center)
            if (leftMovement < -WAVE_HORIZONTAL_THRESHOLD && rightMovement > WAVE_HORIZONTAL_THRESHOLD) {
                if (!expectingInwardMotion) {
                    isWaveMotion = true;
                    expectingInwardMotion = true;
                    movementDesc = "Hands moving outward";
                }
            }
            // Inward motion (hands moving toward center)
            else if (leftMovement > WAVE_HORIZONTAL_THRESHOLD && rightMovement < -WAVE_HORIZONTAL_THRESHOLD) {
                if (expectingInwardMotion) {
                    isWaveMotion = true;
                    expectingInwardMotion = false;
                    movementDesc = "Hands moving inward";
                }
            }
            // Single hand wave is also acceptable
            else if (leftMovedSignificantly && Math.abs(rightMovement) < WAVE_HORIZONTAL_THRESHOLD/2) {
                isWaveMotion = true;
                movementDesc = "Left hand waving";
            }
            else if (rightMovedSignificantly && Math.abs(leftMovement) < WAVE_HORIZONTAL_THRESHOLD/2) {
                isWaveMotion = true;
                movementDesc = "Right hand waving";
            }
        }

        lastMovementDescription = movementDesc.isEmpty() ?
                String.format("L:%.3f R:%.3f", leftMovement, rightMovement) : movementDesc;

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
                lastMovementDescription,
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