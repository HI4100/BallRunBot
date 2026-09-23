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
        @Override public void run() {
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
                long until = p.getLong("command_until", 0);

                if (!running || !capture ||
                        !(cmd.equals("LEFT") || cmd.equals("RIGHT")) ||
                        now > until) {
                    handler.postDelayed(this, 70);
                    return;
                }

                if (!gestureInFlight && now - lastGesture >= 220) {
                    float w = getResources().getDisplayMetrics().widthPixels;
                    float h = getResources().getDisplayMetrics().heightPixels;
                    float playerX = p.getFloat("player_x", w / 2f);
                    float playerY = p.getFloat("player_y", h * 0.70f);
                    float delta = p.getFloat("steer_delta", 45f);
                    long duration = p.getLong("steer_duration", 125);

                    delta = Math.max(28f, Math.min(w * 0.13f, delta));
                    duration = Math.max(90, Math.min(145, duration));

                    // BALL RUN uses normal drag semantics:
                    // left swipe = left, right swipe = right.
                    boolean moveLeft = cmd.equals("LEFT");
                    float startX = Math.max(20f, Math.min(w - 20f, playerX));
                    float endX = moveLeft
                            ? Math.max(20f, startX - delta)
                            : Math.min(w - 20f, startX + delta);

                    if (Math.abs(endX - startX) >= 12f) {
                        sendDrag(startX, playerY, endX, playerY, duration);
                        lastGesture = now;
                    }

                    // Every gesture is consumed. The next frame must issue
                    // the next command, preventing stale/repeated movement.
                    p.edit().putString("command", "NONE")
                            .putLong("command_until", 0).apply();
                }

                handler.postDelayed(this, 70);
            } catch (Throwable t) {
                enabled = false;
                getSharedPreferences("bot", MODE_PRIVATE).edit()
                        .putBoolean("bot_running", false)
                        .putString("command", "NONE")
                        .putLong("command_until", 0).apply();
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
                new GestureDescription.Builder().addStroke(stroke).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        gestureInFlight = false;
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        gestureInFlight = false;
                    }
                }, null);

        if (!ok) gestureInFlight = false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override public void onInterrupt() {
        enabled = false;
        getSharedPreferences("bot", MODE_PRIVATE).edit()
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0).apply();
        if (handler != null) handler.postDelayed(loop, 250);
    }

    @Override public void onDestroy() {
        enabled = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        getSharedPreferences("bot", MODE_PRIVATE).edit()
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0).apply();
        super.onDestroy();
    }
}