package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class ClappingDetector {
    private static final String TAG = "ClappingDetector";

    // Detection parameters
    private static final double CLAP_DISTANCE_THRESHOLD = 0.125; // Distance between hands for clap
    private static final int REQUIRED_CLAP_COUNT = 3; // Number of claps required
    private static final long CLAP_COOLDOWN_MS = 500; // Minimum time between claps
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30 seconds to complete claps

    // Pose landmark indices (MediaPipe Pose)
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;
    private static final int LEFT_INDEX = 19;
    private static final int RIGHT_INDEX = 20;

    // State tracking
    private List<Long> clapTimes;
    private long lastClapTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private ClappingListener listener;
    private DebugListener debugListener;

    // Debug tracking
    private boolean lastHandsVisible = false;
    private double lastWristDistance = 0.0;
    private double lastFingerDistance = 0.0;
    private boolean lastClapState = false;

    public interface ClappingListener {
        void onClappingDetected(int clapCount);
        void onClappingCompleted();
        void onClappingProgress(int currentClaps, int requiredClaps);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onDebugUpdate(String poseStatus, String wristDistance, String fingerDistance, String clapStatus);
    }

    public ClappingDetector() {
        clapTimes = new ArrayList<>();
        reset();
    }

    public void setListener(ClappingListener listener) {
        this.listener = listener;
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting clapping detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onClappingProgress(0, REQUIRED_CLAP_COUNT);
        }

        updateDebugInfo();
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping clapping detection");
        isDetectionActive = false;
        updateDebugInfo();
    }

    public void reset() {
        clapTimes.clear();
        lastClapTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        lastHandsVisible = false;
        lastWristDistance = 0.0;
        lastFingerDistance = 0.0;
        lastClapState = false;
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose detected", "N/A", "N/A", "Inactive");
            return;
        }

        // Check if detection has timed out
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Clapping detection timed out");
            if (listener != null) {
                listener.onDetectionTimeout();
            }
            stopDetection();
            return;
        }

        // Get the first person's landmarks
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        if (landmarks.size() <= Math.max(LEFT_INDEX, RIGHT_INDEX)) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Active - Waiting for pose");
            return;
        }

        // Get hand landmarks
        NormalizedLandmark leftWrist = landmarks.get(LEFT_WRIST);
        NormalizedLandmark rightWrist = landmarks.get(RIGHT_WRIST);
        NormalizedLandmark leftIndex = landmarks.get(LEFT_INDEX);
        NormalizedLandmark rightIndex = landmarks.get(RIGHT_INDEX);

        // Check if hands are visible
        boolean handsVisible = isLandmarkVisible(leftWrist) && isLandmarkVisible(rightWrist) &&
                isLandmarkVisible(leftIndex) && isLandmarkVisible(rightIndex);

        lastHandsVisible = handsVisible;

        if (!handsVisible) {
            updateDebugInfo("Hands not visible", "N/A", "N/A", "Active - Show both hands");
            return;
        }

        // Calculate distances between both wrists AND index fingers
        double wristDistance = calculateDistance(leftWrist, rightWrist);
        double fingerDistance = calculateDistance(leftIndex, rightIndex);

        lastWristDistance = wristDistance;
        lastFingerDistance = fingerDistance;

        // Check if this is a clap - BOTH wrists AND fingers must be close
        boolean isCurrentlyClapping = isClap(wristDistance, fingerDistance);
        lastClapState = isCurrentlyClapping;

        if (isCurrentlyClapping) {
            long currentTime = System.currentTimeMillis();

            // Check cooldown to avoid multiple detections of same clap
            if (currentTime - lastClapTime > CLAP_COOLDOWN_MS) {
                registerClap(currentTime);
            }
        }

        // Update debug information
        String poseStatus = handsVisible ? "Both hands visible" : "Hands not visible";
        String wristDistanceStr = String.format("%.3f (thresh: %.3f)", wristDistance, CLAP_DISTANCE_THRESHOLD);
        String fingerDistanceStr = String.format("%.3f (thresh: %.3f)", fingerDistance, CLAP_DISTANCE_THRESHOLD);
        String clapStatus = String.format("Active - %s (%d/%d claps)",
                isCurrentlyClapping ? "CLAPPING" : "Waiting for clap",
                clapTimes.size(), REQUIRED_CLAP_COUNT);

        updateDebugInfo(poseStatus, wristDistanceStr, fingerDistanceStr, clapStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        // MediaPipe Tasks API uses visibility score
        return landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true;
    }

    private double calculateDistance(NormalizedLandmark point1, NormalizedLandmark point2) {
        double dx = point1.x() - point2.x();
        double dy = point1.y() - point2.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isClap(double wristDistance, double fingerDistance) {
        // Both wrists AND fingers must be close together for a proper clap
        return wristDistance < CLAP_DISTANCE_THRESHOLD && fingerDistance < CLAP_DISTANCE_THRESHOLD;
    }

    private void registerClap(long currentTime) {
        clapTimes.add(currentTime);
        lastClapTime = currentTime;

        Log.d(TAG, "Clap detected! Count: " + clapTimes.size() + "/" + REQUIRED_CLAP_COUNT);

        if (listener != null) {
            listener.onClappingDetected(clapTimes.size());
            listener.onClappingProgress(clapTimes.size(), REQUIRED_CLAP_COUNT);
        }

        // Check if we've reached the required number of claps
        if (clapTimes.size() >= REQUIRED_CLAP_COUNT) {
            Log.d(TAG, "Clapping sequence completed!");
            if (listener != null) {
                listener.onClappingCompleted();
            }
            stopDetection();
        }
    }

    private void updateDebugInfo() {
        updateDebugInfo(
                lastHandsVisible ? "Both hands visible" : "Hands not visible",
                String.format("%.3f", lastWristDistance),
                String.format("%.3f", lastFingerDistance),
                isDetectionActive ? "Active" : "Inactive"
        );
    }

    private void updateDebugInfo(String poseStatus, String wristDistance, String fingerDistance, String clapStatus) {
        if (debugListener != null) {
            debugListener.onDebugUpdate(poseStatus, wristDistance, fingerDistance, clapStatus);
        }
    }

    public boolean isActive() {
        return isDetectionActive;
    }

    public int getCurrentClapCount() {
        return clapTimes.size();
    }

    public int getRequiredClapCount() {
        return REQUIRED_CLAP_COUNT;
    }

    public long getRemainingTime() {
        if (!isDetectionActive) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}