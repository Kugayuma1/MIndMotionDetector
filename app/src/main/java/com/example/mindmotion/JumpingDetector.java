package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class JumpingDetector {
    private static final String TAG = "JumpingDetector";

    // Detection parameters for jumping
    private static final double JUMP_HEIGHT_THRESHOLD = 0.04; // Minimum vertical movement to count as jump
    private static final double FEET_LIFT_THRESHOLD = 0.02; // Both feet must lift off ground
    private static final int REQUIRED_JUMP_COUNT = 3; // Number of jumps required
    private static final long JUMP_COOLDOWN_MS = 600; // Minimum time between jumps
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30 seconds to complete jumps
    private static final double LANDING_THRESHOLD = 0.05; // Threshold to detect landing

    // Pose landmark indices (MediaPipe Pose)
    private static final int LEFT_HIP = 23;
    private static final int RIGHT_HIP = 24;
    private static final int LEFT_KNEE = 25;
    private static final int RIGHT_KNEE = 26;
    private static final int LEFT_ANKLE = 27;
    private static final int RIGHT_ANKLE = 28;
    private static final int LEFT_HEEL = 29;
    private static final int RIGHT_HEEL = 30;
    private static final int LEFT_FOOT_INDEX = 31;
    private static final int RIGHT_FOOT_INDEX = 32;
    private static final int NOSE = 0;

    // State tracking for jump detection
    private List<Long> jumpTimes;
    private long lastJumpTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private JumpingListener listener;
    private DebugListener debugListener;

    // Jump motion tracking
    private double baselineHipHeight = 0.0;
    private double baselineAnkleHeight = 0.0;
    private boolean isJumping = false;
    private boolean wasInAir = false;
    private double peakJumpHeight = 0.0;
    private int framesInAir = 0;
    private static final int MIN_FRAMES_IN_AIR = 1; // Minimum frames to count as jump

    // Debug tracking
    private boolean lastBodyVisible = false;
    private double lastHipHeight = 0.0;
    private double lastAnkleHeight = 0.0;
    private double lastHeightChange = 0.0;
    private boolean lastJumpState = false;

    public interface JumpingListener {
        void onJumpingDetected(int jumpCount);
        void onJumpingCompleted();
        void onJumpingProgress(int currentJumps, int requiredJumps);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onJumpDebugUpdate(String poseStatus, String bodyHeight, String feetStatus, String jumpStatus);
    }

    public JumpingDetector() {
        jumpTimes = new ArrayList<>();
        reset();
    }

    public void setListener(JumpingListener listener) {
        this.listener = listener;
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting jumping detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onJumpingProgress(0, REQUIRED_JUMP_COUNT);
        }

        updateDebugInfo();
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping jumping detection");
        isDetectionActive = false;
        updateDebugInfo();
    }

    public void reset() {
        jumpTimes.clear();
        lastJumpTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        baselineHipHeight = 0.0;
        baselineAnkleHeight = 0.0;
        isJumping = false;
        wasInAir = false;
        peakJumpHeight = 0.0;
        framesInAir = 0;
        lastBodyVisible = false;
        lastHipHeight = 0.0;
        lastAnkleHeight = 0.0;
        lastHeightChange = 0.0;
        lastJumpState = false;
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose detected", "N/A", "N/A", "Inactive");
            return;
        }

        // Check if detection has timed out
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Jumping detection timed out");
            if (listener != null) {
                listener.onDetectionTimeout();
            }
            stopDetection();
            return;
        }

        // Get the first person's landmarks
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        if (landmarks.size() <= Math.max(RIGHT_FOOT_INDEX, LEFT_FOOT_INDEX)) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Active - Waiting for full body");
            return;
        }

        // Get relevant landmarks for jump detection
        NormalizedLandmark leftHip = landmarks.get(LEFT_HIP);
        NormalizedLandmark rightHip = landmarks.get(RIGHT_HIP);
        NormalizedLandmark leftAnkle = landmarks.get(LEFT_ANKLE);
        NormalizedLandmark rightAnkle = landmarks.get(RIGHT_ANKLE);
        NormalizedLandmark leftFoot = landmarks.get(LEFT_FOOT_INDEX);
        NormalizedLandmark rightFoot = landmarks.get(RIGHT_FOOT_INDEX);
        NormalizedLandmark nose = landmarks.get(NOSE);

        // Check if body parts are visible
        boolean bodyVisible = isLandmarkVisible(leftHip) && isLandmarkVisible(rightHip) &&
                isLandmarkVisible(leftAnkle) && isLandmarkVisible(rightAnkle) &&
                isLandmarkVisible(leftFoot) && isLandmarkVisible(rightFoot);

        lastBodyVisible = bodyVisible;

        if (!bodyVisible) {
            updateDebugInfo("Body not fully visible", "N/A", "N/A", "Active - Show full body");
            return;
        }

        // Calculate average hip and ankle heights
        double currentHipHeight = (leftHip.y() + rightHip.y()) / 2.0;
        double currentAnkleHeight = (leftAnkle.y() + rightAnkle.y()) / 2.0;
        double currentFootHeight = (leftFoot.y() + rightFoot.y()) / 2.0;

        lastHipHeight = currentHipHeight;
        lastAnkleHeight = currentAnkleHeight;

        // Initialize baseline on first detection
        if (baselineHipHeight == 0.0) {
            baselineHipHeight = currentHipHeight;
            baselineAnkleHeight = currentAnkleHeight;
            updateDebugInfo("Body visible", "Calibrating baseline...", "On ground", "Active - Jump now!");
            return;
        }

        // Calculate height change (negative = moving up since Y increases downward)
        double hipHeightChange = baselineHipHeight - currentHipHeight;
        double ankleHeightChange = baselineAnkleHeight - currentAnkleHeight;
        lastHeightChange = hipHeightChange;

        // Detect if person is in the air (both feet lifted)
        boolean feetLifted = ankleHeightChange > FEET_LIFT_THRESHOLD;
        boolean significantJump = hipHeightChange > JUMP_HEIGHT_THRESHOLD;

        if (significantJump && feetLifted) {
            // Person is jumping
            if (!wasInAir) {
                isJumping = true;
                wasInAir = true;
                framesInAir = 1;
                peakJumpHeight = hipHeightChange;
                Log.d(TAG, "Jump started - height change: " + hipHeightChange);
            } else {
                framesInAir++;
                if (hipHeightChange > peakJumpHeight) {
                    peakJumpHeight = hipHeightChange;
                }
            }
        } else if (wasInAir && Math.abs(hipHeightChange) < LANDING_THRESHOLD) {
            // Person has landed
            Log.d(TAG, "Landing detected - frames in air: " + framesInAir + ", peak height: " + peakJumpHeight);

            if (framesInAir >= MIN_FRAMES_IN_AIR && peakJumpHeight > JUMP_HEIGHT_THRESHOLD) {
                long currentTime = System.currentTimeMillis();

                // Check cooldown to avoid multiple detections of same jump
                if (currentTime - lastJumpTime > JUMP_COOLDOWN_MS) {
                    registerJump(currentTime);
                }
            }

            // Reset jump tracking
            wasInAir = false;
            isJumping = false;
            framesInAir = 0;
            peakJumpHeight = 0.0;

            // Update baseline after landing
            baselineHipHeight = currentHipHeight;
            baselineAnkleHeight = currentAnkleHeight;
        }

        lastJumpState = wasInAir;

        // Update debug information
        String poseStatus = bodyVisible ? "Full body visible" : "Body not visible";
        String bodyHeightStr = String.format("Hip:%.3f Ankle:%.3f (change:%.3f)",
                currentHipHeight, currentAnkleHeight, hipHeightChange);
        String feetStatusStr = feetLifted ? "IN AIR" : "ON GROUND";
        if (wasInAir) {
            feetStatusStr += String.format(" (frames:%d)", framesInAir);
        }
        String jumpStatus = String.format("Active - %s (%d/%d jumps)",
                wasInAir ? "JUMPING" : "Jump now",
                jumpTimes.size(), REQUIRED_JUMP_COUNT);

        updateDebugInfo(poseStatus, bodyHeightStr, feetStatusStr, jumpStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        // MediaPipe Tasks API uses visibility score
        return landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true;
    }

    private void registerJump(long currentTime) {
        jumpTimes.add(currentTime);
        lastJumpTime = currentTime;

        Log.d(TAG, "Jump detected! Count: " + jumpTimes.size() + "/" + REQUIRED_JUMP_COUNT);

        if (listener != null) {
            listener.onJumpingDetected(jumpTimes.size());
            listener.onJumpingProgress(jumpTimes.size(), REQUIRED_JUMP_COUNT);
        }

        // Check if we've reached the required number of jumps
        if (jumpTimes.size() >= REQUIRED_JUMP_COUNT) {
            Log.d(TAG, "Jumping sequence completed!");
            if (listener != null) {
                listener.onJumpingCompleted();
            }
            stopDetection();
        }
    }

    private void updateDebugInfo() {
        updateDebugInfo(
                lastBodyVisible ? "Full body visible" : "Body not visible",
                String.format("Hip:%.3f Ankle:%.3f", lastHipHeight, lastAnkleHeight),
                lastJumpState ? "IN AIR" : "ON GROUND",
                isDetectionActive ? "Active" : "Inactive"
        );
    }

    private void updateDebugInfo(String poseStatus, String bodyHeight, String feetStatus, String jumpStatus) {
        if (debugListener != null) {
            debugListener.onJumpDebugUpdate(poseStatus, bodyHeight, feetStatus, jumpStatus);
        }
    }

    public boolean isActive() {
        return isDetectionActive;
    }

    public int getCurrentJumpCount() {
        return jumpTimes.size();
    }

    public int getRequiredJumpCount() {
        return REQUIRED_JUMP_COUNT;
    }

    public long getRemainingTime() {
        if (!isDetectionActive) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}