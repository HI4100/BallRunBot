package com.example.ballrunbot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 1001;
    private SharedPreferences prefs;
    private TextView status;
    private final Handler handler = new Handler();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("bot", MODE_PRIVATE);
        stopBot();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        handler.post(refresh);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("🏎️ BALL RUN BOT");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        box.addView(title, full());

        TextView sub = new TextView(this);
        sub.setText("Vision • Adaptive Steering • Safe Control");
        sub.setGravity(Gravity.CENTER);
        sub.setTextSize(13);
        box.addView(sub, margin(0, 2, 0, 14));

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackgroundColor(0xFF202124);
        box.addView(status, margin(0, 0, 0, 14));

        TextView steps = new TextView(this);
        steps.setText("1. Start capture  →  2. Switch to BALL RUN  →  3. Start bot\n" +
                "Keep this app in the background while the game is visible.");
        steps.setTextSize(13);
        steps.setPadding(dp(4), 0, dp(4), dp(12));
        box.addView(steps, full());

        Button cap = button("📱  START SCREEN CAPTURE");
        cap.setOnClickListener(v -> requestCapture());
        box.addView(cap, margin(0, 0, 0, 10));

        Button access = button("♿  ACCESSIBILITY SETTINGS");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(access, margin(0, 0, 0, 10));

        Button start = button("▶  START BOT");
        start.setOnClickListener(v -> startBot());
        box.addView(start, margin(0, 0, 0, 10));

        Button stop = button("■  STOP BOT");
        stop.setOnClickListener(v -> stopBot());
        box.addView(stop, margin(0, 0, 0, 16));

        TextView note = new TextView(this);
        note.setText("Safety: bot starts OFF. It only steers after a confident player + obstacle are detected. " +
                "Vision adapts steering distance and speed from the current error. STOP immediately clears commands.");
        note.setTextSize(12);
        note.setPadding(dp(4), 0, dp(4), 0);
        box.addView(note, full());

        scroll.addView(box);
        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void requestCapture() {
        MediaProjectionManager m =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r == CAPTURE_REQUEST && c == RESULT_OK && d != null) {
            Intent s = new Intent(this, BallRunCaptureService.class);
            s.putExtra("resultCode", c);
            s.putExtra("data", d);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
            else startService(s);
        }
    }

    private void startBot() {
        if (!prefs.getBoolean("capture_active", false)) {
            prefs.edit().putBoolean("bot_running", false)
                    .putString("command", "NONE")
                    .putString("vision_detail", "Start screen capture first")
                    .apply();
            return;
        }
        prefs.edit().putBoolean("bot_running", true)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .apply();
        BallRunAccessibilityService.enableController(true);
    }

    private void stopBot() {
        prefs.edit().putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .apply();
        BallRunAccessibilityService.enableController(false);
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            if (status != null) {
                boolean bot = prefs.getBoolean("bot_running", false);
                boolean cap = prefs.getBoolean("capture_active", false);
                String detail = prefs.getString("vision_detail", "Waiting for capture");
                status.setText((cap ? "🟢 CAPTURE ACTIVE" : "⚪ CAPTURE OFF") +
                        "\nFrames: " + prefs.getLong("frames", 0) +
                        "\nVision: " + detail +
                        "\nBot: " + (bot ? "🟢 RUNNING" : "🔴 STOPPED") +
                        "\nCommand: " + prefs.getString("command", "NONE"));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopBot();
        super.onDestroy();
    }
}
