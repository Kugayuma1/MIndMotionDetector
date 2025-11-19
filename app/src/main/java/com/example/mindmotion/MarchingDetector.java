package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class MarchingDetector {
    private static final String TAG = "MarchingDetector";

    // Detection parameters for marching
    private static final double KNEE_LIFT_THRESHOLD = 0.06; // Knee must lift this much above baseline
    private static final int REQUIRED_MARCH_COUNT = 6; // 6 steps total (3 per leg)
    private static final long MARCH_COOLDOWN_MS = 400; // 400ms between steps
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30 seconds to complete
    private static final int MIN_FRAMES_LIFTED = 2; // Knee must be lifted for at least 2 frames

    // Pose landmark indices (MediaPipe Pose)
    private static final int LEFT_HIP = 23;
    private static final int RIGHT_HIP = 24;
    private static final int LEFT_KNEE = 25;
    private static final int RIGHT_KNEE = 26;
    private static final int LEFT_ANKLE = 27;
    private static final int RIGHT_ANKLE = 28;

    // State tracking
    private List<Long> marchTimes;
    private long lastMarchTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private MarchingListener listener;
    private DebugListener debugListener;

    // Marching state tracking
    private double baselineLeftKneeY = 0.0;
    private double baselineRightKneeY = 0.0;
    private boolean baselineSet = false;
    private String lastLiftedLeg = "none"; // "left", "right", or "none"
    private int leftKneeFramesLifted = 0;
    private int rightKneeFramesLifted = 0;
    private boolean leftKneeWasLifted = false;
    private boolean rightKneeWasLifted = false;

    // Debug tracking
    private boolean lastBodyVisible = false;
    private double lastLeftKneeHeight = 0.0;
    private double lastRightKneeHeight = 0.0;
    private String currentLiftedLeg = "none";

    public interface MarchingListener {
        void onMarchStepDetected(int stepCount);
        void onMarchingCompleted();
        void onMarchingProgress(int currentSteps, int requiredSteps);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onMarchDebugUpdate(String poseStatus, String leftKneeStatus, String rightKneeStatus, String marchStatus);
    }

    public MarchingDetector() {
        marchTimes = new ArrayList<>();
        reset();
    }

    public void setListener(MarchingListener listener) {
        this.listener = listener;
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting marching detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onMarchingProgress(0, REQUIRED_MARCH_COUNT);
        }

        updateDebugInfo();
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping marching detection");
        isDetectionActive = false;
        updateDebugInfo();
    }

    public void reset() {
        marchTimes.clear();
        lastMarchTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        baselineLeftKneeY = 0.0;
        baselineRightKneeY = 0.0;
        baselineSet = false;
        lastLiftedLeg = "none";
        leftKneeFramesLifted = 0;
        rightKneeFramesLifted = 0;
        leftKneeWasLifted = false;
        rightKneeWasLifted = false;
        lastBodyVisible = false;
        lastLeftKneeHeight = 0.0;
        lastRightKneeHeight = 0.0;
        currentLiftedLeg = "none";
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose detected", "N/A", "N/A", "Inactive");
            return;
        }

        // Check if detection has timed out
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Marching detection timed out");
            if (listener != null) {
                listener.onDetectionTimeout();
            }
            stopDetection();
            return;
        }

        // Get the first person's landmarks
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        if (landmarks.size() <= Math.max(RIGHT_ANKLE, LEFT_ANKLE)) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Active - Show full body");
            return;
        }

        // Get relevant landmarks
        NormalizedLandmark leftHip = landmarks.get(LEFT_HIP);
        NormalizedLandmark rightHip = landmarks.get(RIGHT_HIP);
        NormalizedLandmark leftKnee = landmarks.get(LEFT_KNEE);
        NormalizedLandmark rightKnee = landmarks.get(RIGHT_KNEE);
        NormalizedLandmark leftAnkle = landmarks.get(LEFT_ANKLE);
        NormalizedLandmark rightAnkle = landmarks.get(RIGHT_ANKLE);

        // Check if body parts are visible
        boolean bodyVisible = isLandmarkVisible(leftHip) && isLandmarkVisible(rightHip) &&
                isLandmarkVisible(leftKnee) && isLandmarkVisible(rightKnee) &&
                isLandmarkVisible(leftAnkle) && isLandmarkVisible(rightAnkle);

        lastBodyVisible = bodyVisible;

        if (!bodyVisible) {
            updateDebugInfo("Body not fully visible", "N/A", "N/A", "Active - Show full body");
            return;
        }

        // Set baseline on first detection (standing position)
        if (!baselineSet) {
            baselineLeftKneeY = leftKnee.y();
            baselineRightKneeY = rightKnee.y();
            baselineSet = true;
            updateDebugInfo("Body visible", "Calibrating...", "Calibrating...", "Active - Start marching!");
            return;
        }

        // Calculate how much knees have lifted from baseline
        // Negative value = knee lifted up (Y decreases upward)
        double leftKneeChange = baselineLeftKneeY - leftKnee.y();
        double rightKneeChange = baselineRightKneeY - rightKnee.y();

        lastLeftKneeHeight = leftKneeChange;
        lastRightKneeHeight = rightKneeChange;

        // Check if knees are lifted
        boolean leftKneeLifted = leftKneeChange > KNEE_LIFT_THRESHOLD;
        boolean rightKneeLifted = rightKneeChange > KNEE_LIFT_THRESHOLD;

        // Track lifted frames
        if (leftKneeLifted) {
            leftKneeFramesLifted++;
        } else {
            leftKneeFramesLifted = 0;
        }

        if (rightKneeLifted) {
            rightKneeFramesLifted++;
        } else {
            rightKneeFramesLifted = 0;
        }

        // Determine which leg is currently lifted
        if (leftKneeLifted && leftKneeFramesLifted >= MIN_FRAMES_LIFTED) {
            currentLiftedLeg = "left";
        } else if (rightKneeLifted && rightKneeFramesLifted >= MIN_FRAMES_LIFTED) {
            currentLiftedLeg = "right";
        } else {
            currentLiftedLeg = "none";
        }

        long currentTime = System.currentTimeMillis();

        // Detect marching steps - left leg lifted
        if (leftKneeLifted && leftKneeFramesLifted >= MIN_FRAMES_LIFTED && !leftKneeWasLifted) {
            leftKneeWasLifted = true;

            if (currentTime - lastMarchTime > MARCH_COOLDOWN_MS) {
                registerMarchStep(currentTime, "left");
            }
        } else if (!leftKneeLifted) {
            leftKneeWasLifted = false;
        }

        // Detect marching steps - right leg lifted
        if (rightKneeLifted && rightKneeFramesLifted >= MIN_FRAMES_LIFTED && !rightKneeWasLifted) {
            rightKneeWasLifted = true;

            if (currentTime - lastMarchTime > MARCH_COOLDOWN_MS) {
                registerMarchStep(currentTime, "right");
            }
        } else if (!rightKneeLifted) {
            rightKneeWasLifted = false;
        }

        // Update debug information
        String poseStatus = bodyVisible ? "Full body visible" : "Body not visible";
        String leftKneeStr = String.format("%.3f (thresh: %.3f) %s [%d frames]",
                leftKneeChange, KNEE_LIFT_THRESHOLD, leftKneeLifted ? "↑" : "↓", leftKneeFramesLifted);
        String rightKneeStr = String.format("%.3f (thresh: %.3f) %s [%d frames]",
                rightKneeChange, KNEE_LIFT_THRESHOLD, rightKneeLifted ? "↑" : "↓", rightKneeFramesLifted);
        String marchStatus = String.format("Active - %s leg lifted (%d/%d steps)",
                currentLiftedLeg, marchTimes.size(), REQUIRED_MARCH_COUNT);

        updateDebugInfo(poseStatus, leftKneeStr, rightKneeStr, marchStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        return landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true;
    }

    private void registerMarchStep(long currentTime, String leg) {
        marchTimes.add(currentTime);
        lastMarchTime = currentTime;
        lastLiftedLeg = leg;

        Log.d(TAG, "March step detected! (" + leg + " leg) Count: " + marchTimes.size() + "/" + REQUIRED_MARCH_COUNT);

        if (listener != null) {
            listener.onMarchStepDetected(marchTimes.size());
            listener.onMarchingProgress(marchTimes.size(), REQUIRED_MARCH_COUNT);
        }

        // Check if we've reached the required number of steps
        if (marchTimes.size() >= REQUIRED_MARCH_COUNT) {
            Log.d(TAG, "Marching sequence completed!");
            if (listener != null) {
                listener.onMarchingCompleted();
            }
            stopDetection();
        }
    }

    private void updateDebugInfo() {
        updateDebugInfo(
                lastBodyVisible ? "Full body visible" : "Body not visible",
                String.format("L: %.3f", lastLeftKneeHeight),
                String.format("R: %.3f", lastRightKneeHeight),
                isDetectionActive ? "Active" : "Inactive"
        );
    }

    private void updateDebugInfo(String poseStatus, String leftKneeStatus, String rightKneeStatus, String marchStatus) {
        if (debugListener != null) {
            debugListener.onMarchDebugUpdate(poseStatus, leftKneeStatus, rightKneeStatus, marchStatus);
        }
    }

    public boolean isActive() {
        return isDetectionActive;
    }

    public int getCurrentStepCount() {
        return marchTimes.size();
    }

    public int getRequiredStepCount() {
        return REQUIRED_MARCH_COUNT;
    }

    public long getRemainingTime() {
        if (!isDetectionActive) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}