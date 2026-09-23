package com.example.ballrunbot;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public class BallRunCaptureService extends Service {
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private SharedPreferences prefs;
    private long frames = 0;
    private long lastProcess = 0;
    private float smoothedBallX = -1;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private static final int STEP = 8;

    private static final class Component {
        int area;
        int minX, maxX, minY, maxY;
        float cx, cy;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("bot", MODE_PRIVATE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel("capture", "Ball Run Bot", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification notification(String t) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "capture")
                : new Notification.Builder(this);
        return b.setContentTitle("Ball Run Bot")
                .setContentText(t)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        if (in == null) return START_NOT_STICKY;

        int rc = in.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = in.getParcelableExtra("data");

        if (rc != Activity.RESULT_OK || data == null) {
            fail("Capture permission missing");
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(10, notification("Screen capture active"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(10, notification("Screen capture active"));
        }

        setupProjection(data, rc);
        return START_NOT_STICKY;
    }

    private void fail(String msg) {
        prefs.edit().putBoolean("capture_active", false)
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .putString("vision_detail", msg)
                .apply();
        stopSelf();
    }

    private void setupProjection(Intent data, int rc) {
        android.media.projection.MediaProjectionManager m =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = m.getMediaProjection(rc, data);
        if (projection == null) {
            fail("Capture permission failed");
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                fail("Screen capture stopped");
            }
        }, new Handler(Looper.getMainLooper()));

        createDisplay();
    }

    private void createDisplay() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        reader = ImageReader.newInstance(
                w, h, PixelFormat.RGBA_8888, 2);

        reader.setOnImageAvailableListener(r -> {
            Image im = null;
            try {
                im = r.acquireLatestImage();
                if (im == null || busy.get()) return;

                busy.set(true);
                frames++;

                prefs.edit()
                        .putLong("frames", frames)
                        .putBoolean("capture_active", true)
                        .apply();

                long now = SystemClock.uptimeMillis();
                if (now - lastProcess >= 120) {
                    lastProcess = now;
                    analyze(im);
                }
            } catch (Exception e) {
                prefs.edit()
                        .putString("command", "NONE")
                        .putLong("command_until", 0)
                        .putString("vision_detail", "Vision error")
                        .apply();
            } finally {
                if (im != null) im.close();
                busy.set(false);
            }
        }, new Handler(Looper.getMainLooper()));

        display = projection.createVirtualDisplay(
                "BallRunBot", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
    }

    private boolean isPink(ByteBuffer buf, int limit, int rowStride, int pixelStride,
                           int x, int y) {
        int i = y * rowStride + x * pixelStride;
        if (i < 0 || i + 3 >= limit) return false;

        // ImageReader RGBA_8888 is R,G,B,A in increasing byte order.
        int r = buf.get(i) & 255;
        int g = buf.get(i + 1) & 255;
        int b = buf.get(i + 2) & 255;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int spread = max - min;

        // BALL RUN uses vivid pink/purple game elements. Accept both
        // magenta and purple while rejecting grey/white UI and dark pixels.
        return max >= 140 &&
                spread >= 55 &&
                r >= g + 45 &&
                b >= g + 25 &&
                (r + b) >= 300;
    }

    private Component findPlayer(ByteBuffer buf, int limit, int rowStride,
                                 int pixelStride, int w, int h) {
        int gx = (w + STEP - 1) / STEP;
        int gy = (int) Math.ceil((h * 0.46f) / STEP);
        int startY = (int) (h * 0.46f);
        int rows = (int) Math.ceil((h * 0.96f - startY) / STEP);
        if (rows <= 0) return null;

        boolean[] mask = new boolean[gx * rows];

        for (int ry = 0; ry < rows; ry++) {
            int y = startY + ry * STEP;
            if (y >= h) continue;
            for (int ix = 0; ix < gx; ix++) {
                int x = ix * STEP;
                if (x < w && isPink(buf, limit, rowStride, pixelStride, x, y)) {
                    mask[ry * gx + ix] = true;
                }
            }
        }

        Component best = null;
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];

        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;

            int head = 0, tail = 0;
            queue[tail++] = i;
            seen[i] = true;

            int area = 0, minX = gx, maxX = 0, minY = rows, maxY = 0;
            long sx = 0, sy = 0;

            while (head < tail) {
                int p = queue[head++];
                int xg = p % gx;
                int yg = p / gx;
                int px = xg * STEP;
                int py = startY + yg * STEP;

                area++;
                sx += px;
                sy += py;
                if (xg < minX) minX = xg;
                if (xg > maxX) maxX = xg;
                if (yg < minY) minY = yg;
                if (yg > maxY) maxY = yg;

                int left = p - 1, right = p + 1, up = p - gx, down = p + gx;
                if (xg > 0 && mask[left] && !seen[left]) { seen[left] = true; queue[tail++] = left; }
                if (xg + 1 < gx && mask[right] && !seen[right]) { seen[right] = true; queue[tail++] = right; }
                if (yg > 0 && mask[up] && !seen[up]) { seen[up] = true; queue[tail++] = up; }
                if (yg + 1 < rows && mask[down] && !seen[down]) { seen[down] = true; queue[tail++] = down; }
            }

            int width = (maxX - minX + 1) * STEP;
            int height = (maxY - minY + 1) * STEP;
            float ratio = height == 0 ? 99f : (float) width / height;
            float centerY = sy / (float) area;

            // A player ball should be a compact, roughly round component near the bottom.
            if (area < 18 || area > 1200) continue;
            if (centerY < h * 0.56f) continue;
            if (ratio < 0.45f || ratio > 2.2f) continue;

            if (best == null || centerY > best.cy || area > best.area * 1.35f) {
                best = new Component();
                best.area = area;
                best.minX = minX * STEP;
                best.maxX = maxX * STEP;
                best.minY = startY + minY * STEP;
                best.maxY = startY + maxY * STEP;
                best.cx = sx / (float) area;
                best.cy = centerY;
            }
        }

        return best;
    }

    private Component findNearestObstacle(ByteBuffer buf, int limit, int rowStride,
                                          int pixelStride, int w, int h, float ballX) {
        int startY = (int) (h * 0.18f);
        int endY = (int) (h * 0.70f);
        int gx = (w + STEP - 1) / STEP;
        int rows = (int) Math.ceil((endY - startY) / (float) STEP);

        boolean[] mask = new boolean[gx * rows];
        for (int ry = 0; ry < rows; ry++) {
            int y = startY + ry * STEP;
            for (int ix = 0; ix < gx; ix++) {
                int x = ix * STEP;
                if (x < w && isPink(buf, limit, rowStride, pixelStride, x, y)) {
                    mask[ry * gx + ix] = true;
                }
            }
        }

        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        Component best = null;
        float bestScore = Float.MAX_VALUE;

        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;

            int head = 0, tail = 0;
            queue[tail++] = i;
            seen[i] = true;

            int area = 0, minX = gx, maxX = 0, minY = rows, maxY = 0;
            long sx = 0, sy = 0;

            while (head < tail) {
                int p = queue[head++];
                int xg = p % gx;
                int yg = p / gx;
                int px = xg * STEP;
                int py = startY + yg * STEP;

                area++;
                sx += px;
                sy += py;
                if (xg < minX) minX = xg;
                if (xg > maxX) maxX = xg;
                if (yg < minY) minY = yg;
                if (yg > maxY) maxY = yg;

                int left = p - 1, right = p + 1, up = p - gx, down = p + gx;
                if (xg > 0 && mask[left] && !seen[left]) { seen[left] = true; queue[tail++] = left; }
                if (xg + 1 < gx && mask[right] && !seen[right]) { seen[right] = true; queue[tail++] = right; }
                if (yg > 0 && mask[up] && !seen[up]) { seen[up] = true; queue[tail++] = up; }
                if (yg + 1 < rows && mask[down] && !seen[down]) { seen[down] = true; queue[tail++] = down; }
            }

            float cx = sx / (float) area;
            float cy = sy / (float) area;
            int width = (maxX - minX + 1) * STEP;
            int height = (maxY - minY + 1) * STEP;
            float ratio = height == 0 ? 99f : (float) width / height;

            // Ignore tiny text/noise and very thin track lines.
            if (area < 22 || area > 6000) continue;
            if (ratio < 0.25f || ratio > 4.0f) continue;
            if (cy >= h * 0.70f || cy >= h * 0.90f) continue;
            if (cy >= h * 0.58f && Math.abs(cx - ballX) < w * 0.12f) continue;

            // Prefer the obstacle physically closest to the player.
            float dy = Math.max(0, h * 0.82f - cy);
            float dx = Math.abs(cx - ballX);
            float score = dy * 0.9f + dx * 0.20f;

            if (score < bestScore) {
                bestScore = score;
                best = new Component();
                best.area = area;
                best.minX = minX * STEP;
                best.maxX = maxX * STEP;
                best.minY = startY + minY * STEP;
                best.maxY = startY + maxY * STEP;
                best.cx = cx;
                best.cy = cy;
            }
        }

        return best;
    }

    private void clearCommand(String detail) {
        prefs.edit()
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .putString("vision_detail", detail)
                .apply();
    }

    private void analyze(Image image) {
        Image.Plane p = image.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int row = p.getRowStride();
        int pix = p.getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();
        int limit = buf.limit();

        Component player = findPlayer(buf, limit, row, pix, w, h);

        if (player == null) {
            smoothedBallX = -1;
            clearCommand("NO PLAYER • switch to BALL RUN");
            return;
        }

        float ballX = player.cx;
        if (smoothedBallX < 0) {
            smoothedBallX = ballX;
        } else {
            float jump = Math.abs(ballX - smoothedBallX);
            if (jump > w * 0.28f) {
                clearCommand("PLAYER REJECTED • unstable frame");
                return;
            }
            smoothedBallX = smoothedBallX * 0.65f + ballX * 0.35f;
        }

        Component obstacle = findNearestObstacle(buf, limit, row, pix, w, h, smoothedBallX);

        if (obstacle == null) {
            clearCommand("PLAYER x=" + (int) smoothedBallX + " • NO OBSTACLE");
            return;
        }

        float error = obstacle.cx - smoothedBallX;
        float absError = Math.abs(error);

        // Ignore obstacles that are already well away from the player's path.
        float deadZone = Math.max(w * 0.055f, 42f);
        if (absError < deadZone) {
            clearCommand("PLAYER x=" + (int) smoothedBallX +
                    " • OBSTACLE x=" + (int) obstacle.cx + " • CENTER");
            return;
        }

        // Adaptive control: larger error = stronger/faster correction.
        float delta = Math.max(35f, Math.min(w * 0.18f, absError * 0.38f));
        long duration = (long) Math.max(75, Math.min(180, 75 + absError * 0.18f));
        long validFor = Math.max(140, Math.min(280, duration + 90));

        String cmd = error > 0 ? "LEFT" : "RIGHT";

        prefs.edit()
                .putString("command", cmd)
                .putFloat("steer_delta", delta)
                .putLong("steer_duration", duration)
                .putLong("command_until", SystemClock.uptimeMillis() + validFor)
                .putString("vision_detail",
                        "PLAYER x=" + (int) smoothedBallX +
                        " • OBSTACLE x=" + (int) obstacle.cx +
                        " • " + cmd + " " + (int) delta + "px/" + duration + "ms")
                .apply();
    }

    @Override
    public void onDestroy() {
        prefs.edit()
                .putBoolean("capture_active", false)
                .putBoolean("bot_running", false)
                .putString("command", "NONE")
                .putLong("command_until", 0)
                .apply();

        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent i) {
        return null;
    }
}
