package com.example.ballrunbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;

public class BallRunAccessibilityService extends AccessibilityService {
    private static volatile boolean enabled = false;
    private Handler handler;
    private boolean gestureInFlight = false;
    private GestureDescription.StrokeDescription currentStroke;
    private float fingerX = -1f;
    private float fingerY = -1f;
    private long lastVisionTarget = 0;

    public static void enableController(boolean on) {
        enabled = on;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        handler.post(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (handler == null) return;
            try {
                android.content.SharedPreferences p =
                        getSharedPreferences("bot", MODE_PRIVATE);
                boolean running = enabled && p.getBoolean("bot_running", false);
                boolean capture = p.getBoolean("capture_active", false);
                String cmd = p.getString("command", "NONE");
                long now = SystemClock.uptimeMillis();
                long until = p.getLong("command_until", 0);

                if (!running || !capture) {
                    if (!gestureInFlight) releaseFinger();
                    handler.postDelayed(this, 60);
                    return;
                }

                if ("MOVE".equals(cmd) && now <= until) {
                    float w = getResources().getDisplayMetrics().widthPixels;
                    float h = getResources().getDisplayMetrics().heightPixels;
                    float target = p.getFloat("steer_target_x", Float.NaN);
                    float playerX = p.getFloat("player_x", w / 2f);
                    float playerY = p.getFloat("player_y", h * .70f);
                    long duration = p.getLong("steer_duration", 100);

                    if (Float.isNaN(target) || target < 1 || target > w - 1) {
                        if (!gestureInFlight) releaseFinger();
                    } else {
                        if (fingerX < 0) fingerX = clamp(playerX, 20, w - 20);
                        fingerY = clamp(playerY, 40, h - 40);
                        target = clamp(target, 20, w - 20);
                        lastVisionTarget = now;

                        if (!gestureInFlight) {
                            dispatchNextSegment(target, duration, true);
                        }
                    }
                } else if (now - lastVisionTarget > 280) {
                    if (!gestureInFlight) releaseFinger();
                }

                handler.postDelayed(this, 35);
            } catch (Throwable t) {
                enabled = false;
                forceStopState();
                handler.postDelayed(loop, 500);
            }
        }
    };

    private void dispatchNextSegment(float target, long duration, boolean keepDown) {
        if (gestureInFlight) return;

        float dx = target - fingerX;
        float endX = Math.abs(dx) < 2f ? fingerX : target;
        long d = Math.max(45, Math.min(190, duration));

        Path path = new Path();
        path.moveTo(fingerX, fingerY);
        float midX = fingerX + (endX - fingerX) * .55f;
        path.lineTo(midX, fingerY);
        path.lineTo(endX, fingerY);

        GestureDescription.StrokeDescription stroke;
        if (currentStroke == null) {
            stroke = new GestureDescription.StrokeDescription(path, 0, d, keepDown);
        } else {
            stroke = currentStroke.continueStroke(path, 0, d, keepDown);
        }

        currentStroke = stroke;
        gestureInFlight = true;
        final float finalX = endX;

        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        fingerX = finalX;
                        gestureInFlight = false;

                        android.content.SharedPreferences p =
                                getSharedPreferences("bot", MODE_PRIVATE);
                        boolean running = enabled &&
                                p.getBoolean("bot_running", false) &&
                                p.getBoolean("capture_active", false);
                        String cmd = p.getString("command", "NONE");
                        long until = p.getLong("command_until", 0);

                        if (running && "MOVE".equals(cmd) &&
                                SystemClock.uptimeMillis() <= until) {
                            float nextTarget = p.getFloat("steer_target_x", fingerX);
                            long nextDuration = p.getLong("steer_duration", 90);
                            dispatchNextSegment(nextTarget, nextDuration, true);
                        } else {
                            releaseFromCompletedStroke();
                        }
                    }

                    @Override public void onCancelled(GestureDescription g) {
                        gestureInFlight = false;
                        currentStroke = null;
                        fingerX = -1f;
                    }
                }, null);

        if (!ok) {
            gestureInFlight = false;
            currentStroke = null;
            fingerX = -1f;
        }
    }

    private void releaseFromCompletedStroke() {
        if (currentStroke == null || fingerX < 0 || fingerY < 0) {
            currentStroke = null;
            fingerX = -1f;
            return;
        }
        try {
            Path path = new Path();
            path.moveTo(fingerX, fingerY);
            GestureDescription.StrokeDescription release =
                    currentStroke.continueStroke(path, 0, 20, false);
            currentStroke = null;
            gestureInFlight = true;

            dispatchGesture(
                    new GestureDescription.Builder().addStroke(release).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) {
                            gestureInFlight = false;
                            fingerX = -1f;
                        }
                        @Override public void onCancelled(GestureDescription g) {
                            gestureInFlight = false;
                            fingerX = -1f;
                        }
                    }, null);
        } catch (Throwable ignored) {
            currentStroke = null;
            gestureInFlight = false;
            fingerX = -1f;
        }
    }

    private void releaseFinger() {
        if (gestureInFlight) return;
        releaseFromCompletedStroke();
    }

    private void forceStopState() {
        getSharedPreferences("bot", MODE_PRIVATE).edit()
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .putString("vision_detail", "CONTROL ERROR • STOPPED")
                .apply();
        if (!gestureInFlight) releaseFromCompletedStroke();
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {
        enabled = false;
        forceStopState();
    }

    @Override public void onDestroy() {
        enabled = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        forceStopState();
        super.onDestroy();
    }
}
