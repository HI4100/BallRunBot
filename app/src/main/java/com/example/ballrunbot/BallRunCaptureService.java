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
        float meanV;
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

    private ArrayList<Component> findObstacles(ByteBuffer buf, int limit, int rowStride,
                                               int pixelStride, int w, int h) {
        int startY = (int) (h * 0.28f);
        int endY = (int) (h * 0.72f);
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
        ArrayList<Component> out = new ArrayList<>();

        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;

            int head = 0, tail = 0;
            queue[tail++] = i;
            seen[i] = true;

            int area = 0, minX = gx, maxX = 0, minY = rows, maxY = 0;
            long sx = 0, sy = 0, sumV = 0;

            while (head < tail) {
                int p = queue[head++];
                int xg = p % gx;
                int yg = p / gx;
                int px = xg * STEP;
                int py = startY + yg * STEP;

                area++;
                sx += px;
                sy += py;

                int bi = py * rowStride + px * pixelStride;
                if (bi >= 0 && bi + 2 < limit) {
                    int r = buf.get(bi) & 255;
                    int g = buf.get(bi + 1) & 255;
                    int b = buf.get(bi + 2) & 255;
                    sumV += Math.max(r, Math.max(g, b));
                }

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
            float cy = sy / (float) area;
            float meanV = sumV / (float) Math.max(1, area);

            if (area < 18 || area > 6000) continue;
            if (cy < h * 0.30f || cy > h * 0.72f) continue;
            if (ratio < 0.20f || ratio > 5.0f) continue;

            // The game's real blocks are bright, solid purple. The magenta
            // tunnel/side-wall decoration visible in the supplied gameplay
            // video is noticeably darker and must not become an obstacle.
            if (meanV < 225f) continue;

            Component c = new Component();
            c.area = area;
            c.minX = minX * STEP;
            c.maxX = Math.min(w - 1, (maxX + 1) * STEP - 1);
            c.minY = startY + minY * STEP;
            c.maxY = Math.min(h - 1, startY + (maxY + 1) * STEP - 1);
            c.cx = sx / (float) area;
            c.cy = cy;
            c.meanV = meanV;
            out.add(c);
        }

        return out;
    }

    private Component chooseTargetObstacle(ArrayList<Component> obstacles, float ballX, int w, int h) {
        Component threat = null;
        float best = Float.MAX_VALUE;

        for (Component o : obstacles) {
            // Perspective: the ball occupies less horizontal world-space at
            // the obstacle's depth than it does at the bottom of the screen.
            float ballRadius = Math.max(24f, (w * 0.09f));
            float depthScale = 0.55f;
            float clearance = Math.max(22f, ballRadius * depthScale);

            float left = o.minX - clearance;
            float right = o.maxX + clearance;

            if (ballX >= left && ballX <= right) {
                float urgency = Math.max(0, h * 0.78f - o.cy);
                float centerDistance = Math.abs(o.cx - ballX);
                float score = urgency + centerDistance * 0.15f;
                if (score < best) {
                    best = score;
                    threat = o;
                }
            }
        }

        return threat;
    }

    private void planSteering(ByteBuffer buf, int limit, int rowStride, int pixelStride,
                              int w, int h, float ballX) {
        ArrayList<Component> obstacles = findObstacles(buf, limit, rowStride, pixelStride, w, h);

        if (obstacles.isEmpty()) {
            clearCommand("PLAYER x=" + (int) ballX + " • CLEAR");
            return;
        }

        Component threat = chooseTargetObstacle(obstacles, ballX, w, h);
        if (threat == null) {
            clearCommand("PLAYER x=" + (int) ballX + " • OBSTACLE(S) CLEAR");
            return;
        }

        float ballRadius = Math.max(24f, w * 0.09f);
        float clearance = Math.max(22f, ballRadius * 0.55f);

        // Build two escape targets around the threatening block.
        float leftTarget = threat.minX - clearance;
        float rightTarget = threat.maxX + clearance;

        // If several blocks exist, reject an escape target that is inside
        // another block's expanded interval. This handles two-block gates.
        float leftPenalty = 0f;
        float rightPenalty = 0f;
        for (Component o : obstacles) {
            float oc = Math.max(22f, ballRadius * 0.55f);
            if (leftTarget >= o.minX - oc && leftTarget <= o.maxX + oc) leftPenalty += 100000f;
            if (rightTarget >= o.minX - oc && rightTarget <= o.maxX + oc) rightPenalty += 100000f;
        }

        leftTarget = Math.max(w * 0.08f, Math.min(w * 0.92f, leftTarget));
        rightTarget = Math.max(w * 0.08f, Math.min(w * 0.92f, rightTarget));

        float leftCost = Math.abs(ballX - leftTarget) + leftPenalty;
        float rightCost = Math.abs(ballX - rightTarget) + rightPenalty;

        float targetX;
        if (leftCost < rightCost) {
            targetX = leftTarget;
        } else {
            targetX = rightTarget;
        }

        float error = targetX - ballX;
        float deadZone = Math.max(w * 0.018f, 18f);

        if (Math.abs(error) < deadZone) {
            clearCommand("PLAYER x=" + (int) ballX +
                    " • THREAT x=" + (int) threat.cx + " • HOLD");
            return;
        }

        // Small closed-loop nudges. Never make a giant jump toward a lane.
        float delta = Math.max(w * 0.025f, Math.min(w * 0.085f, Math.abs(error) * 0.30f));
        long duration = (long) Math.max(60, Math.min(115, 60 + Math.abs(error) * 0.08f));
        long validFor = Math.max(130, Math.min(220, duration + 70));
        String cmd = error < 0 ? "LEFT" : "RIGHT";

        prefs.edit()
                .putString("command", cmd)
                .putFloat("steer_delta", delta)
                .putLong("steer_duration", duration)
                .putFloat("steer_target_x", targetX)
                .putLong("command_until", SystemClock.uptimeMillis() + validFor)
                .putString("vision_detail",
                        "PLAYER x=" + (int) ballX +
                        " • THREAT x=" + (int) threat.cx +
                        " • TARGET x=" + (int) targetX +
                        " • " + cmd)
                .apply();
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
        prefs.edit()
                .putFloat("player_x", player.cx)
                .putFloat("player_y", player.cy)
                .apply();
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

        planSteering(buf, limit, row, pix, w, h, smoothedBallX);
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
