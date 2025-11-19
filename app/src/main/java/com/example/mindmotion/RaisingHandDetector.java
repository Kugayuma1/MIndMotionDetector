package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class RaisingHandDetector {
    private static final String TAG = "RaisingHandDetector";

    // Detection parameters (base values — adaptive threshold computed at runtime)
    private static final double BASE_HAND_RAISE_THRESHOLD = 0.08; // fallback if shoulder width unknown
    private static final int REQUIRED_RAISE_COUNT = 3;
    private static final long RAISE_COOLDOWN_MS = 1500;
    private static final long DETECTION_TIMEOUT_MS = 30000;
    private static final long MIN_RAISE_DURATION_MS = 300;

    // Smoothing / stability
    private static final double SMOOTH_ALPHA = 0.4; // EMA smoothing factor (0..1) for hand heights
    private static final double MIN_SHOULDER_WIDTH = 0.05; // avoid division by zero and too small thresholds
    private static final double ADAPTIVE_THRESHOLD_RATIO = 0.25; // threshold = shoulderWidth * ratio

    // Minimum arm length and horizontal separation to avoid false positives/spikes
    private static final double MIN_ARM_LENGTH = 0.06; // elbow->wrist distance (normalized)
    private static final double MIN_HORIZ_SEP = 0.03;  // shoulder->wrist horizontal separation

    // Pose landmark indices (MediaPipe Pose)
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;
    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_ELBOW = 13;
    private static final int RIGHT_ELBOW = 14;
    private static final int NOSE = 0;

    // State tracking
    private List<Long> raiseTimes;
    private long lastRaiseTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private RaisingHandListener listener;
    private DebugListener debugListener;

    // Hand raise tracking - FIXED STATE MACHINE
    private boolean wasHandRaised = false;
    private long handRaisedStartTime = 0;
    private String lastRaisedHand = "none";
    private boolean raiseAlreadyCounted = false; // Prevent counting same raise multiple times

    // Debug / smoothing tracking (use doubles for precision)
    private boolean lastHandsVisible = false;
    private double lastLeftHandHeight = 0.0;   // smoothed left hand height
    private double lastRightHandHeight = 0.0;  // smoothed right hand height
    private String currentRaisedHand = "none";

    public interface RaisingHandListener {
        void onHandRaised(int raiseCount);
        void onHandRaisingCompleted();
        void onHandRaisingProgress(int currentRaises, int requiredRaises);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onRaiseDebugUpdate(String poseStatus, String leftHandHeight, String rightHandHeight, String raiseStatus);
    }

    public RaisingHandDetector() {
        raiseTimes = new ArrayList<>();
        reset();
    }

    public void setListener(RaisingHandListener listener) {
        this.listener = listener;
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void startDetection() {
        Log.d(TAG, "Starting hand raising detection...");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onHandRaisingProgress(0, REQUIRED_RAISE_COUNT);
        }

        updateDebugInfo();
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping hand raising detection");
        isDetectionActive = false;
        updateDebugInfo();
    }

    public void reset() {
        raiseTimes.clear();
        lastRaiseTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        wasHandRaised = false;
        handRaisedStartTime = 0;
        lastRaisedHand = "none";
        raiseAlreadyCounted = false;
        lastHandsVisible = false;
        lastLeftHandHeight = 0.0;
        lastRightHandHeight = 0.0;
        currentRaisedHand = "none";
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose detected", "N/A", "N/A", "Inactive");
            return;
        }

        // Check timeout
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Hand raising detection timed out");
            if (listener != null) {
                listener.onDetectionTimeout();
            }
            stopDetection();
            return;
        }

        // Get landmarks for first person
        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        if (landmarks.size() <= Math.max(RIGHT_WRIST, RIGHT_SHOULDER)) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Active - Show hands");
            return;
        }

        NormalizedLandmark leftWrist = landmarks.get(LEFT_WRIST);
        NormalizedLandmark rightWrist = landmarks.get(RIGHT_WRIST);
        NormalizedLandmark leftShoulder = landmarks.get(LEFT_SHOULDER);
        NormalizedLandmark rightShoulder = landmarks.get(RIGHT_SHOULDER);
        NormalizedLandmark leftElbow = landmarks.get(LEFT_ELBOW);
        NormalizedLandmark rightElbow = landmarks.get(RIGHT_ELBOW);
        NormalizedLandmark nose = landmarks.get(NOSE);

        // Check visibility (require both shoulders and both wrists visible)
        boolean handsVisible = isLandmarkVisible(leftWrist) && isLandmarkVisible(rightWrist) &&
                isLandmarkVisible(leftShoulder) && isLandmarkVisible(rightShoulder);

        lastHandsVisible = handsVisible;

        if (!handsVisible) {
            updateDebugInfo("Hands not visible", "N/A", "N/A", "Active - Show hands");
            return;
        }

        // Compute shoulder width as scale factor
        double shoulderWidth = Math.abs(leftShoulder.x() - rightShoulder.x());
        if (Double.isNaN(shoulderWidth) || shoulderWidth <= 0) {
            shoulderWidth = MIN_SHOULDER_WIDTH;
        }
        shoulderWidth = Math.max(shoulderWidth, MIN_SHOULDER_WIDTH);

        // Adaptive threshold based on shoulder width
        double adaptiveThreshold = shoulderWidth * ADAPTIVE_THRESHOLD_RATIO;
        // Clamp fallback if tiny or weird
        adaptiveThreshold = Math.max(adaptiveThreshold, BASE_HAND_RAISE_THRESHOLD * 0.12); // keep minimum tiny fallback

        // Raw heights (positive when wrist is above shoulder because y increases downward)
        double rawLeftHeight = leftShoulder.y() - leftWrist.y();
        double rawRightHeight = rightShoulder.y() - rightWrist.y();

        // Compute arm length and horizontal separation to reduce false positives/spikes
        double leftArmLen = distance(leftElbow, leftWrist);
        double rightArmLen = distance(rightElbow, rightWrist);
        double leftHoriz = Math.abs(leftShoulder.x() - leftWrist.x());
        double rightHoriz = Math.abs(rightShoulder.x() - rightWrist.x());

        // Smooth heights (exponential moving average)
        lastLeftHandHeight = ema(lastLeftHandHeight, rawLeftHeight, SMOOTH_ALPHA);
        lastRightHandHeight = ema(lastRightHandHeight, rawRightHeight, SMOOTH_ALPHA);

        // Apply additional sanity checks: if arm length too short or horizontal sep too small, treat as not raised
        boolean leftArmValid = leftArmLen >= MIN_ARM_LENGTH && leftHoriz >= MIN_HORIZ_SEP;
        boolean rightArmValid = rightArmLen >= MIN_ARM_LENGTH && rightHoriz >= MIN_HORIZ_SEP;

        // Decide left/right raised based on smoothed height exceeding adaptiveThreshold AND arm validity
        boolean leftHandRaised = leftArmValid && lastLeftHandHeight > adaptiveThreshold;
        boolean rightHandRaised = rightArmValid && lastRightHandHeight > adaptiveThreshold;
        boolean anyHandRaised = leftHandRaised || rightHandRaised;

        // Determine which hand is raised
        if (leftHandRaised && rightHandRaised) {
            currentRaisedHand = "both";
        } else if (leftHandRaised) {
            currentRaisedHand = "left";
        } else if (rightHandRaised) {
            currentRaisedHand = "right";
        } else {
            currentRaisedHand = "none";
        }

        long currentTime = System.currentTimeMillis();

        // STATE MACHINE: Only count raises when hand is ABOVE threshold and stable
        if (anyHandRaised) {
            if (!wasHandRaised) {
                // Just started holding above threshold
                wasHandRaised = true;
                handRaisedStartTime = currentTime;
                lastRaisedHand = currentRaisedHand;
                raiseAlreadyCounted = false;
                Log.d(TAG, "Hand raised (entered): " + currentRaisedHand
                        + " | rawL=" + String.format("%.3f", rawLeftHeight)
                        + " rawR=" + String.format("%.3f", rawRightHeight)
                        + " adaptTh=" + String.format("%.3f", adaptiveThreshold));
            } else if (!raiseAlreadyCounted) {
                long raiseDuration = currentTime - handRaisedStartTime;

                // Only count if:
                // 1) held long enough
                // 2) cooldown since last counted raise
                if (raiseDuration >= MIN_RAISE_DURATION_MS &&
                        currentTime - lastRaiseTime > RAISE_COOLDOWN_MS) {
                    registerHandRaise(currentTime);
                    raiseAlreadyCounted = true;
                }
            }
            // if raiseAlreadyCounted == true: wait until lowered to allow next raise
        } else {
            // Hand lowered -> reset per-raise flags
            if (wasHandRaised) {
                Log.d(TAG, "Hand lowered - ready for next raise");
                wasHandRaised = false;
                handRaisedStartTime = 0;
                raiseAlreadyCounted = false;
            }
        }

        // Debug strings
        String poseStatus = handsVisible ? "Hands visible" : "Hands not visible";
        String leftHeightStr = String.format("%.3f (thresh: %.3f) %s%s",
                lastLeftHandHeight, adaptiveThreshold,
                leftHandRaised ? "✓" : "✗",
                leftArmValid ? "" : " (armInvalid)");
        String rightHeightStr = String.format("%.3f (thresh: %.3f) %s%s",
                lastRightHandHeight, adaptiveThreshold,
                rightHandRaised ? "✓" : "✗",
                rightArmValid ? "" : " (armInvalid)");

        String raiseStatus;
        if (wasHandRaised) {
            long duration = currentTime - handRaisedStartTime;
            String countedStr = raiseAlreadyCounted ? " [COUNTED]" : "";
            raiseStatus = String.format("Active - Holding %s hand (%dms)%s (%d/%d raises)",
                    currentRaisedHand, duration, countedStr, raiseTimes.size(), REQUIRED_RAISE_COUNT);
        } else {
            raiseStatus = String.format("Active - Raise hand (%d/%d raises)",
                    raiseTimes.size(), REQUIRED_RAISE_COUNT);
        }

        updateDebugInfo(poseStatus, leftHeightStr, rightHeightStr, raiseStatus);
    }

    private boolean isLandmarkVisible(NormalizedLandmark landmark) {
        return landmark == null ? false : (landmark.visibility().isPresent() ? landmark.visibility().get() > 0.5 : true);
    }

    private void registerHandRaise(long currentTime) {
        raiseTimes.add(currentTime);
        lastRaiseTime = currentTime;

        Log.d(TAG, "Hand raise registered! Count: " + raiseTimes.size() + "/" + REQUIRED_RAISE_COUNT);

        if (listener != null) {
            listener.onHandRaised(raiseTimes.size());
            listener.onHandRaisingProgress(raiseTimes.size(), REQUIRED_RAISE_COUNT);
        }

        if (raiseTimes.size() >= REQUIRED_RAISE_COUNT) {
            Log.d(TAG, "Hand raising sequence completed!");
            if (listener != null) {
                listener.onHandRaisingCompleted();
            }
            stopDetection();
        }
    }

    private void updateDebugInfo() {
        updateDebugInfo(
                lastHandsVisible ? "Hands visible" : "Hands not visible",
                String.format("L: %.3f", lastLeftHandHeight),
                String.format("R: %.3f", lastRightHandHeight),
                isDetectionActive ? "Active" : "Inactive"
        );
    }

    private void updateDebugInfo(String poseStatus, String leftHandHeight, String rightHandHeight, String raiseStatus) {
        if (debugListener != null) {
            debugListener.onRaiseDebugUpdate(poseStatus, leftHandHeight, rightHandHeight, raiseStatus);
        }
    }

    public boolean isActive() {
        return isDetectionActive;
    }

    public int getCurrentRaiseCount() {
        return raiseTimes.size();
    }

    public int getRequiredRaiseCount() {
        return REQUIRED_RAISE_COUNT;
    }

    public long getRemainingTime() {
        if (!isDetectionActive) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }

    // ----------------- Helper methods -----------------

    private double ema(double prev, double current, double alpha) {
        // Exponential moving average; if prev is 0 (initial), make it closer to current
        if (prev == 0.0) return current;
        return prev * (1.0 - alpha) + current * alpha;
    }

    private double distance(NormalizedLandmark a, NormalizedLandmark b) {
        if (a == null || b == null) return 0.0;
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
