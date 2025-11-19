package com.example.mindmotion;

import android.util.Log;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.List;

public class MarchingDetector {
    private static final String TAG = "MarchingDetector";

    // Detection parameters
    private static final int REQUIRED_MARCH_COUNT = 6; // 6 total steps
    private static final long MARCH_COOLDOWN_MS = 400; // minimum time between steps
    private static final long DETECTION_TIMEOUT_MS = 30000; // 30s
    private static final int MIN_FRAMES_LIFTED = 2;

    // Dynamic threshold parameters
    private static final double BASE_THRESHOLD = 0.06;
    private static final double HEIGHT_MULTIPLIER = 0.25;

    // Baseline averaging
    private static final int BASELINE_FRAMES = 10;
    private int baselineCounter = 0;
    private double sumLeftKnee = 0.0;
    private double sumRightKnee = 0.0;

    // Pose landmark indices
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
    private boolean baselineSet = false;

    private double baselineLeftKneeY = 0.0;
    private double baselineRightKneeY = 0.0;

    private int leftKneeFramesLifted = 0;
    private int rightKneeFramesLifted = 0;

    private boolean leftKneeWasLifted = false;
    private boolean rightKneeWasLifted = false;

    // Debug
    private boolean lastBodyVisible = false;
    private double lastLeftKneeHeight = 0.0;
    private double lastRightKneeHeight = 0.0;
    private String currentLiftedLeg = "none";

    // Listeners
    public interface MarchingListener {
        void onMarchStepDetected(int stepCount);
        void onMarchingCompleted();
        void onMarchingProgress(int currentSteps, int requiredSteps);
        void onDetectionTimeout();
    }

    public interface DebugListener {
        void onMarchDebugUpdate(
                String poseStatus,
                String leftKneeStatus,
                String rightKneeStatus,
                String marchStatus
        );
    }

    private MarchingListener listener;
    private DebugListener debugListener;

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
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null)
            listener.onMarchingProgress(0, REQUIRED_MARCH_COUNT);

        sendDebug("Initializing...", "Wait", "Wait", "Calibrating baseline...");
    }

    public void stopDetection() {
        isDetectionActive = false;
        sendDebug("Stopped", "N/A", "N/A", "Inactive");
    }

    public void reset() {
        marchTimes.clear();
        lastMarchTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;

        // Baseline reset
        baselineSet = false;
        baselineCounter = 0;
        sumLeftKnee = 0.0;
        sumRightKnee = 0.0;

        // Lift tracking
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
            sendDebug("No Pose", "N/A", "N/A", "Inactive");
            return;
        }

        // Timeout check
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            if (listener != null)
                listener.onDetectionTimeout();
            stopDetection();
            return;
        }

        List<NormalizedLandmark> lm = result.landmarks().get(0);
        if (lm.size() <= RIGHT_ANKLE) {
            sendDebug("Landmarks missing", "N/A", "N/A", "Adjust camera");
            return;
        }

        NormalizedLandmark leftHip = lm.get(LEFT_HIP);
        NormalizedLandmark rightHip = lm.get(RIGHT_HIP);
        NormalizedLandmark leftKnee = lm.get(LEFT_KNEE);
        NormalizedLandmark rightKnee = lm.get(RIGHT_KNEE);
        NormalizedLandmark leftAnkle = lm.get(LEFT_ANKLE);
        NormalizedLandmark rightAnkle = lm.get(RIGHT_ANKLE);

        boolean bodyVisible =
                isVisible(leftHip) && isVisible(rightHip) &&
                        isVisible(leftKnee) && isVisible(rightKnee) &&
                        isVisible(leftAnkle) && isVisible(rightAnkle);

        lastBodyVisible = bodyVisible;

        if (!bodyVisible) {
            sendDebug("Body not fully visible", "N/A", "N/A", "Adjust camera");
            return;
        }

        //
        // ⚠ Baseline Calibration (first 10 frames)
        //
        if (!baselineSet) {
            sumLeftKnee += leftKnee.y();
            sumRightKnee += rightKnee.y();
            baselineCounter++;

            sendDebug("Calibrating (" + baselineCounter + "/10)", "Wait", "Wait", "Stand still");

            if (baselineCounter >= BASELINE_FRAMES) {
                baselineLeftKneeY = sumLeftKnee / BASELINE_FRAMES;
                baselineRightKneeY = sumRightKnee / BASELINE_FRAMES;
                baselineSet = true;
                sendDebug("Baseline Set!", "OK", "OK", "Start marching!");
            }
            return;
        }

        //
        // Compute knee lift height
        //
        double leftChange = baselineLeftKneeY - leftKnee.y();
        double rightChange = baselineRightKneeY - rightKnee.y();

        lastLeftKneeHeight = leftChange;
        lastRightKneeHeight = rightChange;

        //
        // Dynamic thresholds based on leg length
        //
        double leftLegLength = Math.abs(leftHip.y() - leftKnee.y());
        double rightLegLength = Math.abs(rightHip.y() - rightKnee.y());

        double leftThreshold = BASE_THRESHOLD + leftLegLength * HEIGHT_MULTIPLIER;
        double rightThreshold = BASE_THRESHOLD + rightLegLength * HEIGHT_MULTIPLIER;

        boolean leftLifted = leftChange > leftThreshold;
        boolean rightLifted = rightChange > rightThreshold;

        //
        // Frame filters
        //
        if (leftLifted) leftKneeFramesLifted++;
        else leftKneeFramesLifted = 0;

        if (rightLifted) rightKneeFramesLifted++;
        else rightKneeFramesLifted = 0;

        if (leftKneeFramesLifted >= MIN_FRAMES_LIFTED) currentLiftedLeg = "left";
        else if (rightKneeFramesLifted >= MIN_FRAMES_LIFTED) currentLiftedLeg = "right";
        else currentLiftedLeg = "none";

        long now = System.currentTimeMillis();

        //
        // Step detection – LEFT
        //
        if (leftLifted && leftKneeFramesLifted >= MIN_FRAMES_LIFTED && !leftKneeWasLifted) {
            leftKneeWasLifted = true;

            if (now - lastMarchTime > MARCH_COOLDOWN_MS)
                registerStep(now, "left");

        } else if (!leftLifted && leftKneeFramesLifted == 0) {
            leftKneeWasLifted = false;
        }

        //
        // Step detection – RIGHT
        //
        if (rightLifted && rightKneeFramesLifted >= MIN_FRAMES_LIFTED && !rightKneeWasLifted) {
            rightKneeWasLifted = true;

            if (now - lastMarchTime > MARCH_COOLDOWN_MS)
                registerStep(now, "right");

        } else if (!rightLifted && rightKneeFramesLifted == 0) {
            rightKneeWasLifted = false;
        }

        //
        // Debug display
        //
        sendDebug(
                "Body OK",
                String.format("%.3f (thr %.3f) [%d]", leftChange, leftThreshold, leftKneeFramesLifted),
                String.format("%.3f (thr %.3f) [%d]", rightChange, rightThreshold, rightKneeFramesLifted),
                "Lift: " + currentLiftedLeg + "   Steps: " + marchTimes.size() + "/" + REQUIRED_MARCH_COUNT
        );
    }

    private boolean isVisible(NormalizedLandmark lm) {
        return lm.visibility().isPresent()
                ? lm.visibility().get() > 0.3
                : true;
    }

    private void registerStep(long time, String leg) {
        marchTimes.add(time);
        lastMarchTime = time;

        Log.d(TAG, "Step detected (" + leg + ")  " + marchTimes.size() + "/" + REQUIRED_MARCH_COUNT);

        if (listener != null) {
            listener.onMarchStepDetected(marchTimes.size());
            listener.onMarchingProgress(marchTimes.size(), REQUIRED_MARCH_COUNT);
        }

        if (marchTimes.size() >= REQUIRED_MARCH_COUNT) {
            if (listener != null)
                listener.onMarchingCompleted();
            stopDetection();
        }
    }

    private void sendDebug(String poseStatus, String left, String right, String marchStatus) {
        if (debugListener != null)
            debugListener.onMarchDebugUpdate(poseStatus, left, right, marchStatus);
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
        if (!isDetectionActive) return 0;
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}
