package com.example.mindmotion;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SpeechRecognitionManager implements RecognitionListener {
    private static final String TAG = "SpeechRecognitionManager";
    private static final int RESTART_DELAY_MS = 1000;
    private static final int MAX_RESTART_ATTEMPTS = 5;

    public interface SpeechListener {
        void onWordDetected(String word);
        void onSpeechError(String error);
        void onSpeechStatusChanged(boolean isListening);
    }

    private Context context;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private SpeechListener listener;
    private Handler mainHandler;

    private boolean isListening = false;
    private boolean shouldRestart = true;
    private int restartAttempts = 0;
    private Set<String> processedWords = new HashSet<>();

    // Restart timer
    private Runnable restartRunnable = new Runnable() {
        @Override
        public void run() {
            if (shouldRestart && !isListening) {
                startListening();
            }
        }
    };

    public SpeechRecognitionManager(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        setupSpeechRecognizer();
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(this);

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        // Enable continuous listening
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
    }

    public void setListener(SpeechListener listener) {
        this.listener = listener;
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void startListening() {
        if (!hasPermission()) {
            notifyError("Microphone permission not granted");
            return;
        }

        if (isListening) {
            Log.d(TAG, "Already listening");
            return;
        }

        if (speechRecognizer == null) {
            setupSpeechRecognizer();
        }

        try {
            speechRecognizer.startListening(speechRecognizerIntent);
            Log.d(TAG, "Started speech recognition");
        } catch (Exception e) {
            Log.e(TAG, "Error starting speech recognition", e);
            notifyError("Failed to start speech recognition: " + e.getMessage());
        }
    }

    public void stopListening() {
        shouldRestart = false;
        mainHandler.removeCallbacks(restartRunnable);

        if (speechRecognizer != null && isListening) {
            speechRecognizer.stopListening();
        }

        Log.d(TAG, "Speech recognition stopped");
    }

    public void pauseListening() {
        shouldRestart = false;
        mainHandler.removeCallbacks(restartRunnable);

        if (speechRecognizer != null && isListening) {
            speechRecognizer.cancel();
        }
    }

    public void resumeListening() {
        shouldRestart = true;
        restartAttempts = 0;
        startListening();
    }

    public boolean isListening() {
        return isListening;
    }

    private void scheduleRestart() {
        if (shouldRestart && restartAttempts < MAX_RESTART_ATTEMPTS) {
            restartAttempts++;
            mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS);
            Log.d(TAG, "Scheduled restart attempt " + restartAttempts);
        } else if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            notifyError("Maximum restart attempts reached");
            shouldRestart = false;
        }
    }

    private void notifyError(String error) {
        Log.e(TAG, error);
        if (listener != null) {
            listener.onSpeechError(error);
        }
    }

    private void notifyStatusChanged(boolean listening) {
        isListening = listening;
        if (listener != null) {
            listener.onSpeechStatusChanged(listening);
        }
    }

    // RecognitionListener implementation
    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "Ready for speech");
        restartAttempts = 0; // Reset on successful start
        notifyStatusChanged(true);
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Audio level changed - can be used for visual feedback
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Audio buffer received
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "End of speech");
    }

    @Override
    public void onError(int error) {
        String errorMsg = getErrorMessage(error);
        Log.e(TAG, "Speech recognition error: " + errorMsg);

        notifyStatusChanged(false);

        // Handle different error types
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                // These are recoverable - restart listening
                scheduleRestart();
                break;

            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                notifyError("Microphone permission denied");
                shouldRestart = false;
                break;

            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                // Wait a bit longer before restarting
                mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS * 2);
                break;

            default:
                // Other errors - try restarting
                scheduleRestart();
                break;
        }
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognitionResults(matches);

        notifyStatusChanged(false);
        scheduleRestart(); // Continue listening
    }

    @Override
    public void onPartialResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognitionResults(matches);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Handle recognition events
    }

    private void processRecognitionResults(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return;

        String fullText = matches.get(0).toLowerCase().trim();
        Log.d(TAG, "Recognized: " + fullText);

        // Split into individual words and process each
        String[] words = fullText.split("\\s+");
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z]", "").toLowerCase();

            if (word.length() > 1 && !processedWords.contains(word)) {
                processedWords.add(word);

                // Notify listener of new word
                if (listener != null) {
                    listener.onWordDetected(word);
                }

                Log.d(TAG, "New word detected: " + word);

                // Clean up old words to prevent memory buildup
                if (processedWords.size() > 1000) {
                    processedWords.clear();
                }
            }
        }
    }

    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No match found";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Unknown error: " + error;
        }
    }

    public void cleanup() {
        stopListening();

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        processedWords.clear();
        mainHandler.removeCallbacks(restartRunnable);
    }
}