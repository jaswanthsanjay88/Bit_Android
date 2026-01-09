package com.atharva.ollama.util;

import android.os.Handler;
import android.os.Looper;

/**
 * Helper class to simulate smooth typing effect for streaming text.
 */
public class StreamTyper {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OnTypingUpdateListener listener;
    private final long delayPerChar;
    
    // The full text that we want to display eventually
    private String targetText = "";
    // The text currently displayed
    private String currentText = "";
    
    private boolean isTyping = false;
    private Runnable typingRunnable;

    public interface OnTypingUpdateListener {
        void onUpdate(String text);
    }

    public StreamTyper(long delayPerMs, OnTypingUpdateListener listener) {
        this.delayPerChar = delayPerMs;
        this.listener = listener;
    }

    /**
     * Update the target text. The typer will gradually catch up to this text.
     */
    public void updateTarget(String newTarget) {
        if (newTarget == null) return;
        
        // If the new target is shorter than current (e.g. reset), reset immediately
        if (newTarget.length() < currentText.length()) {
            currentText = newTarget;
            targetText = newTarget;
            listener.onUpdate(currentText);
            return;
        }

        this.targetText = newTarget;
        startTyping();
    }

    private void startTyping() {
        if (isTyping) return;
        
        typingRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentText.length() < targetText.length()) {
                    isTyping = true;
                    // Append next character
                    currentText = targetText.substring(0, currentText.length() + 1);
                    listener.onUpdate(currentText);
                    
                    // Add some variance to make it look human or just handle large chunks faster
                    long delay = delayPerChar;
                    
                    // Speed up if we explain falling behind (large chunk arrived)
                    int remaining = targetText.length() - currentText.length();
                    if (remaining > 50) delay = 1; // Very fast if far behind
                    else if (remaining > 20) delay = 5;
                    else if (remaining > 5) delay = 10;
                    
                    handler.postDelayed(this, delay);
                } else {
                    isTyping = false;
                    // Ensure we match exactly at the end
                    if (!currentText.equals(targetText)) {
                        currentText = targetText;
                        listener.onUpdate(currentText);
                    }
                }
            }
        };
        
        handler.post(typingRunnable);
    }

    public void reset() {
        handler.removeCallbacksAndMessages(null);
        isTyping = false;
        currentText = "";
        targetText = "";
    }
}
