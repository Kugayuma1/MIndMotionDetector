package com.example.mindmotion;

import android.util.Log;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.List;

public class JumpingDetector {
    private static final String TAG = "JumpingDetector";

    // Detection parameters
    private static final int REQUIRED_JUMP_COUNT = 3;
    private static final long JUMP_COOLDOWN_MS = 400;
    private static final long DETECTION_TIMEOUT_MS = 30000;

    // Pose landmark indices
    private static final int LEFT_HIP = 23;
    private static final int RIGHT_HIP = 24;
    private static final int LEFT_ANKLE = 27;
    private static final int RIGHT_ANKLE = 28;
    private static final int LEFT_KNEE = 25;
    private static final int RIGHT_KNEE = 26;
    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;

    // Jump tracking
    private List<Long> jumpTimes;
    private long lastJumpTime;
    private long detectionStartTime;
    private boolean isDetectionActive;
    private JumpingListener listener;
    private DebugListener debugListener;

    // Rolling window for real-time baseline (last 15 frames = ~0.5 seconds)
    private static final int BASELINE_WINDOW = 15;
    private List<Double> recentHipHeights = new ArrayList<>();
    private List<Double> recentAnkleHeights = new ArrayList<>();

    // Velocity and acceleration tracking
    private double lastHipY = 0.0;
    private double lastVelocity = 0.0;
    private List<Double> recentVelocities = new ArrayList<>();

    // Jump state
    private enum JumpPhase { WAITING, DETECTED_RISE, AIRBORNE, DETECTED_FALL }
    private JumpPhase jumpPhase = JumpPhase.WAITING;
    private double maxHeightInJump = 0.0;
    private int airborneFrames = 0;

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
        Log.d(TAG, "Starting jumping detection for kids - NO calibration needed!");
        reset();
        isDetectionActive = true;
        detectionStartTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onJumpingProgress(0, REQUIRED_JUMP_COUNT);
        }

        updateDebugInfo("Ready!", "Start jumping anytime", "No need to stand still", "Jump when ready!");
    }

    public void stopDetection() {
        Log.d(TAG, "Stopping jumping detection");
        isDetectionActive = false;
        updateDebugInfo("Stopped", "N/A", "N/A", "Inactive");
    }

    public void reset() {
        jumpTimes.clear();
        lastJumpTime = 0;
        detectionStartTime = 0;
        isDetectionActive = false;
        recentHipHeights.clear();
        recentAnkleHeights.clear();
        recentVelocities.clear();
        jumpPhase = JumpPhase.WAITING;
        maxHeightInJump = 0.0;
        airborneFrames = 0;
        lastHipY = 0.0;
        lastVelocity = 0.0;
    }

    public void analyzePoseResult(PoseLandmarkerResult result) {
        if (!isDetectionActive || result == null || result.landmarks().isEmpty()) {
            updateDebugInfo("No pose", "N/A", "N/A", "Inactive");
            return;
        }

        // Timeout check
        if (System.currentTimeMillis() - detectionStartTime > DETECTION_TIMEOUT_MS) {
            Log.d(TAG, "Detection timed out");
            if (listener != null) listener.onDetectionTimeout();
            stopDetection();
            return;
        }

        List<NormalizedLandmark> landmarks = result.landmarks().get(0);
        if (landmarks.size() <= RIGHT_KNEE) {
            updateDebugInfo("Insufficient landmarks", "N/A", "N/A", "Show full body");
            return;
        }

        // Get landmarks
        NormalizedLandmark leftHip = landmarks.get(LEFT_HIP);
        NormalizedLandmark rightHip = landmarks.get(RIGHT_HIP);
        NormalizedLandmark leftAnkle = landmarks.get(LEFT_ANKLE);
        NormalizedLandmark rightAnkle = landmarks.get(RIGHT_ANKLE);
        NormalizedLandmark leftKnee = landmarks.get(LEFT_KNEE);
        NormalizedLandmark rightKnee = landmarks.get(RIGHT_KNEE);
        NormalizedLandmark leftShoulder = landmarks.get(LEFT_SHOULDER);
        NormalizedLandmark rightShoulder = landmarks.get(RIGHT_SHOULDER);

        // Check visibility
        if (!isVisible(leftHip) || !isVisible(rightHip) ||
                !isVisible(leftAnkle) || !isVisible(rightAnkle)) {
            updateDebugInfo("Body not visible", "N/A", "N/A", "Show full body in camera");
            return;
        }

        // Calculate current positions
        double hipY = (leftHip.y() + rightHip.y()) / 2.0;
        double ankleY = (leftAnkle.y() + rightAnkle.y()) / 2.0;
        double kneeY = (leftKnee.y() + rightKnee.y()) / 2.0;
        double shoulderY = (leftShoulder.y() + rightShoulder.y()) / 2.0;

        // Body measurements (for normalization)
        double torsoLength = Math.max(Math.abs(shoulderY - hipY), 0.08);
        double hipToAnkle = ankleY - hipY; // Positive value (ankle below hip)

        // Add to rolling window
        recentHipHeights.add(hipY);
        recentAnkleHeights.add(ankleY);
        if (recentHipHeights.size() > BASELINE_WINDOW) {
            recentHipHeights.remove(0);
            recentAnkleHeights.remove(0);
        }

        // Need at least 5 frames to establish baseline
        if (recentHipHeights.size() < 5) {
            lastHipY = hipY;
            updateDebugInfo("Initializing...", "Collecting data", "Move around freely!",
                    String.format("%d/5 frames", recentHipHeights.size()));
            return;
        }

        // Calculate ROLLING BASELINE from recent LOW points (when person is on ground)
        // Sort and take the lower 60% values (when feet are more likely on ground)
        List<Double> sortedHips = new ArrayList<>(recentHipHeights);
        sortedHips.sort(Double::compareTo);
        int topIndex = (int)(sortedHips.size() * 0.6);
        double groundBaseline = 0;
        for (int i = sortedHips.size() - 1; i >= topIndex; i--) {
            groundBaseline += sortedHips.get(i);
        }
        groundBaseline /= (sortedHips.size() - topIndex);

        // Calculate velocity (change per frame) - NEGATIVE = moving up
        double velocity = hipY - lastHipY;
        lastHipY = hipY;

        // Smooth velocity
        recentVelocities.add(velocity);
        if (recentVelocities.size() > 3) recentVelocities.remove(0);
        double smoothVelocity = recentVelocities.stream().mapToDouble(v -> v).average().orElse(velocity);

        // Acceleration (change in velocity)
        double acceleration = smoothVelocity - lastVelocity;
        lastVelocity = smoothVelocity;

        // Height relative to recent ground baseline (POSITIVE = above ground)
        double relativeHeight = (groundBaseline - hipY) / torsoLength;

        // Leg extension detection (legs straighten when jumping)
        double hipKneeDistance = Math.abs(kneeY - hipY);
        double kneeAnkleDistance = Math.abs(ankleY - kneeY);
        double legExtension = (hipKneeDistance + kneeAnkleDistance) / torsoLength;

        // Update max height in this jump
        if (relativeHeight > maxHeightInJump) {
            maxHeightInJump = relativeHeight;
        }

        // JUMP DETECTION STATE MACHINE
        switch (jumpPhase) {
            case WAITING:
                // Detect ANY UPWARD MOTION (ULTRA SENSITIVE - detects even 1cm!)
                // 1. ANY upward velocity
                // 2. Body rising even slightly above recent baseline
                boolean anyUpwardMotion = smoothVelocity < -0.001; // ULTRA sensitive!
                boolean slightRise = relativeHeight > 0.01; // Only 1cm needed!

                if (anyUpwardMotion && slightRise) {
                    jumpPhase = JumpPhase.DETECTED_RISE;
                    maxHeightInJump = relativeHeight;
                    airborneFrames = 1;
                    Log.d(TAG, String.format("🚀 RISE detected! vel=%.4f, height=%.3f", smoothVelocity, relativeHeight));
                }
                break;

            case DETECTED_RISE:
                airborneFrames++;

                // Check if still rising or at peak (VERY LENIENT)
                if (smoothVelocity < 0.002 && relativeHeight > 0.02) {
                    // Still going up or at peak
                    jumpPhase = JumpPhase.AIRBORNE;
                    Log.d(TAG, String.format("✈️ AIRBORNE! Peak height: %.3f", maxHeightInJump));
                } else if (relativeHeight < 0.01 || airborneFrames > 12) {
                    // False alarm - didn't actually get airborne
                    Log.d(TAG, "❌ False jump - resetting");
                    jumpPhase = JumpPhase.WAITING;
                    maxHeightInJump = 0;
                    airborneFrames = 0;
                }
                break;

            case AIRBORNE:
                airborneFrames++;

                // Detect FALLING (velocity becomes positive = moving down)
                if (smoothVelocity > 0.002) {
                    jumpPhase = JumpPhase.DETECTED_FALL;
                    Log.d(TAG, String.format("⬇️ FALLING detected! vel=%.4f", smoothVelocity));
                }

                // Timeout if airborne too long (probably an error)
                if (airborneFrames > 25) {
                    Log.d(TAG, "⚠️ Airborne too long - resetting");
                    jumpPhase = JumpPhase.WAITING;
                    maxHeightInJump = 0;
                    airborneFrames = 0;
                }
                break;

            case DETECTED_FALL:
                airborneFrames++;

                // Detect LANDING (VERY LENIENT for tiny jumps)
                boolean velocityStable = Math.abs(smoothVelocity) < 0.012;
                boolean nearGround = relativeHeight < 0.15;

                if (velocityStable && nearGround) {
                    // JUMP COMPLETED!
                    boolean validJump = maxHeightInJump > 0.015 && airborneFrames >= 2; // Only 1.5cm + 2 frames!

                    if (validJump) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastJumpTime > JUMP_COOLDOWN_MS) {
                            registerJump(currentTime, maxHeightInJump * torsoLength);
                            Log.d(TAG, String.format("✅ JUMP REGISTERED! Height: %.2fcm, Frames: %d",
                                    maxHeightInJump * torsoLength * 100, airborneFrames));
                        } else {
                            Log.d(TAG, "⏱️ Jump too soon - cooldown active");
                        }
                    } else {
                        Log.d(TAG, String.format("❌ Invalid jump - height: %.3f, frames: %d",
                                maxHeightInJump, airborneFrames));
                    }

                    // Reset for next jump
                    jumpPhase = JumpPhase.WAITING;
                    maxHeightInJump = 0;
                    airborneFrames = 0;
                }

                // Timeout
                if (airborneFrames > 20) {
                    Log.d(TAG, "⚠️ Landing timeout - resetting");
                    jumpPhase = JumpPhase.WAITING;
                    maxHeightInJump = 0;
                    airborneFrames = 0;
                }
                break;
        }

        // Debug output
        String phaseEmoji = jumpPhase == JumpPhase.WAITING ? "⏳" :
                jumpPhase == JumpPhase.DETECTED_RISE ? "🚀" :
                        jumpPhase == JumpPhase.AIRBORNE ? "✈️" : "⬇️";

        String poseStatus = "Full body visible";
        String heightStr = String.format("H:%.3f V:%.4f A:%.4f Leg:%.2f",
                relativeHeight, smoothVelocity, acceleration, legExtension);
        String phaseStr = String.format("%s %s (f:%d max:%.3f)",
                phaseEmoji, jumpPhase.name(), airborneFrames, maxHeightInJump);
        String jumpStr = String.format("%.0fcm | %d/%d jumps",
                maxHeightInJump * torsoLength * 100, jumpTimes.size(), REQUIRED_JUMP_COUNT);

        updateDebugInfo(poseStatus, heightStr, phaseStr, jumpStr);
    }

    private boolean isVisible(NormalizedLandmark lm) {
        return !lm.visibility().isPresent() || lm.visibility().get() > 0.5;
    }

    private void registerJump(long currentTime, double jumpHeightMeters) {
        jumpTimes.add(currentTime);
        lastJumpTime = currentTime;

        Log.d(TAG, String.format("🎯 JUMP #%d COUNTED! Height: %.1fcm",
                jumpTimes.size(), jumpHeightMeters * 100));

        if (listener != null) {
            listener.onJumpingDetected(jumpTimes.size());
            listener.onJumpingProgress(jumpTimes.size(), REQUIRED_JUMP_COUNT);
        }

        if (jumpTimes.size() >= REQUIRED_JUMP_COUNT) {
            Log.d(TAG, "🎉🎉🎉 ALL JUMPS COMPLETED!");
            if (listener != null) listener.onJumpingCompleted();
            stopDetection();
        }
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
        if (!isDetectionActive) return 0;
        long elapsed = System.currentTimeMillis() - detectionStartTime;
        return Math.max(0, DETECTION_TIMEOUT_MS - elapsed);
    }
}