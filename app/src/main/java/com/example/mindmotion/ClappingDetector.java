package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class ClappingDetector {
    private static final String TAG = "ClappingDetector";

    // Detection parameters
    private static final double CLAP_DISTANCE_THRESHOLD = 0.15; // Distance between hands for clap
    private static final int REQUIRED_CLAP_COUNT = 3; // Number of claps required
    private static final long CLAP_COOLDOWN_MS = 300; // Minimum time between claps
    private static final long DETECTION_TIMEOUT_MS = 60000; // 60 seconds to complete claps

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

    public interface ClappingListener {
        void onClappingDetected(int clapCount);
        void onClappingCompleted();
        void onClappingProgress(int currentClaps, int requiredClaps);
        void onDetectionTimeout();
    }

    public ClappingDetector() {
        clapTimes = new ArrayList<>();
        reset();
    }

    public void setListener(ClappingListener listener) {
        this.listener = listener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting clapping detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onClappingProgress(0, REQUIRED_CLAP_COUNT);
        }
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping clapping detection");
        isDetectionActive = false;
    }

    public void reset() {
        clapTimes.clear();
        lastClapTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
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
            return;
        }

        // Get hand landmarks
        NormalizedLandmark leftWrist = landmarks.get(LEFT_WRIST);
        NormalizedLandmark rightWrist = landmarks.get(RIGHT_WRIST);
        NormalizedLandmark leftIndex = landmarks.get(LEFT_INDEX);
        NormalizedLandmark rightIndex = landmarks.get(RIGHT_INDEX);

        // Check if hands are visible (using visibility threshold)
        if (!isLandmarkVisible(leftWrist) || !isLandmarkVisible(rightWrist) ||
                !isLandmarkVisible(leftIndex) || !isLandmarkVisible(rightIndex)) {
            return;
        }

        // Calculate distance between hands (using index fingers for better accuracy)
        double distance = calculateDistance(leftIndex, rightIndex);

        // Check if this is a clap
        if (isClap(distance)) {
            long currentTime = System.currentTimeMillis();

            // Check cooldown to avoid multiple detections of same clap
            if (currentTime - lastClapTime > CLAP_COOLDOWN_MS) {
                registerClap(currentTime);
            }
        }
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

    private boolean isClap(double handDistance) {
        return handDistance < CLAP_DISTANCE_THRESHOLD;
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