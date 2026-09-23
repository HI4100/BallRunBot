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
    private long lastGesture = 0;

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
        public void run() {
            if (handler == null) return;

            try {
                if (!enabled) {
                    handler.postDelayed(this, 250);
                    return;
                }

                android.content.SharedPreferences p =
                        getSharedPreferences("bot", MODE_PRIVATE);

                boolean running = p.getBoolean("bot_running", false);
                boolean capture = p.getBoolean("capture_active", false);
                String cmd = p.getString("command", "NONE");
                long now = SystemClock.uptimeMillis();
                long validUntil = p.getLong("command_until", 0);

                if (!running || !capture ||
                        !(cmd.equals("LEFT") || cmd.equals("RIGHT")) ||
                        now > validUntil) {
                    handler.postDelayed(this, 80);
                    return;
                }

                if (!gestureInFlight && now - lastGesture >= 180) {
                    float w = getResources().getDisplayMetrics().widthPixels;
                    float h = getResources().getDisplayMetrics().heightPixels;

                    float playerX = p.getFloat("player_x", w / 2f);
                    float playerY = p.getFloat("player_y", h * 0.82f);
                    float cx = playerX;
                    float y = playerY;

                    float delta = p.getFloat("steer_delta", 60f);
                    long duration = p.getLong("steer_duration", 100);

                    // Start the drag on the detected ball and use the calibrated game direction.\n                    // The game may map a finger drag opposite to the ball movement.\n                    // Default is inverted based on the observed control behavior.\n                    // Keep every gesture inside conservative limits.
                    delta = Math.max(30f, Math.min(w * 0.18f, delta));
                    duration = Math.max(70, Math.min(180, duration));

                    boolean invert = p.getBoolean("invert_steering", true);
                    boolean moveLeft = cmd.equals("LEFT") ^ invert;
                    float dx = moveLeft ? -delta : delta;

                    sendDrag(cx, y, cx + dx, y, duration);
                    lastGesture = now;

                    p.edit()
                            .putString("command", "NONE")
                            .putLong("command_until", 0)
                            .putFloat("last_gesture_player_x", playerX)
                            .putLong("last_gesture_at", now)
                            .putBoolean("last_gesture_logical_left", logicalLeft)
                            .apply();
                }

                handler.postDelayed(this, 80);
            } catch (Throwable t) {
                enabled = false;
                getSharedPreferences("bot", MODE_PRIVATE)
                        .edit()
                        .putBoolean("bot_running", false)
                        .putString("command", "NONE")
                        .putLong("command_until", 0)
                        .apply();
                handler.postDelayed(this, 500);
            }
        }
    };

    private void sendDrag(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);

        gestureInFlight = true;

        boolean ok = dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        gestureInFlight = false;
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        gestureInFlight = false;
                    }
                },
                null);

        if (!ok) gestureInFlight = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {
        enabled = false;
        getSharedPreferences("bot", MODE_PRIVATE)
                .edit()
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .apply();
        if (handler != null) handler.postDelayed(loop, 250);
    }

    @Override
    public void onDestroy() {
        enabled = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        getSharedPreferences("bot", MODE_PRIVATE)
                .edit()
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .apply();
        super.onDestroy();
    }
}
