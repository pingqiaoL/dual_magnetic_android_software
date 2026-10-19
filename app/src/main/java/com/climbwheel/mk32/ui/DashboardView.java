package com.climbwheel.mk32.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal 16:10 picture-in-picture dashboard for the MK32 1280x800 display.
 * The default view is full-screen video with a thickness card. Tapping the card
 * swaps the video and thickness panes without stopping telemetry processing.
 */
public final class DashboardView extends View {
    public interface ActionListener { void onAction(String action); }

    private static final float DESIGN_W = 1600f;
    private static final float DESIGN_H = 1000f;
    private static final float HEADER_H = 82f;

    private static final int HEADER = Color.rgb(7, 23, 38);
    private static final int PANEL = Color.rgb(9, 29, 47);
    private static final int GRID = Color.rgb(48, 73, 94);
    private static final int TEXT = Color.rgb(232, 240, 247);
    private static final int MUTED = Color.rgb(173, 190, 204);
    private static final int CYAN = Color.rgb(50, 224, 237);
    private static final int GREEN = Color.rgb(78, 231, 124);
    private static final int AMBER = Color.rgb(255, 179, 71);
    private static final int RED = Color.rgb(245, 73, 83);
    private static final int GRAY = Color.rgb(137, 156, 172);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, RectF> actions = new HashMap<>();

    private TelemetrySnapshot telemetry = TelemetrySnapshot.builder().build();
    private ActionListener actionListener;
    private String communicationError;
    private boolean localRecording;
    private boolean videoPrimary = true;
    private boolean videoStreamAvailable;
    private int remoteBattery = -1;
    private float[] waveform = new float[0];
    private String transientMessage;
    private long transientMessageUntil;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;
    private long touchDownAt;
    private String pressedAction;

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 1000);
        }
    };

    public DashboardView(Context context) {
        super(context);
        setBackgroundColor(PANEL);
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(1f);
    }

    public void setActionListener(ActionListener listener) { actionListener = listener; }

    public void setTelemetry(TelemetrySnapshot value) {
        telemetry = value;
        communicationError = null;
        if (value.waveform.length > 0) waveform = Arrays.copyOf(value.waveform, value.waveform.length);
        postInvalidateOnAnimation();
    }

    public void setCommunicationError(String value) {
        communicationError = value;
        postInvalidateOnAnimation();
    }

    public void setLocalRecording(boolean value) {
        localRecording = value;
        postInvalidateOnAnimation();
    }

    public void setRemoteBattery(int value) {
        remoteBattery = value >= 0 ? Math.min(100, value) : -1;
        postInvalidateOnAnimation();
    }

    public void setVideoStreamAvailable(boolean value) {
        videoStreamAvailable = value;
        postInvalidateOnAnimation();
    }

    public void showMessage(String value) {
        transientMessage = value;
        transientMessageUntil = SystemClock.uptimeMillis() + 3200;
        postInvalidateOnAnimation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(statusTicker);
        post(statusTicker);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(statusTicker);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        scale = Math.min(getWidth() / DESIGN_W, getHeight() / DESIGN_H);
        offsetX = (getWidth() - DESIGN_W * scale) / 2f;
        offsetY = (getHeight() - DESIGN_H * scale) / 2f;

        canvas.drawColor(PANEL);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        actions.clear();

        if (videoPrimary) drawVideoPrimary(canvas); else drawDataPrimary(canvas);
        drawHeader(canvas);
        drawTransientMessage(canvas);
        canvas.restore();
    }

    private void drawHeader(Canvas c) {
        fill(c, HEADER, 0, 0, DESIGN_W, HEADER_H, 0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.rgb(55, 77, 96));
        paint.setStrokeWidth(1.2f);
        c.drawLine(0, HEADER_H, DESIGN_W, HEADER_H, paint);
        text(c, "磁轮检测机器人", 24, 53, 29, Color.WHITE, Paint.Align.LEFT, true);

        long now = System.currentTimeMillis();
        boolean demo = telemetry.linkState == TelemetrySnapshot.LinkState.DEMO;
        boolean nxFresh = telemetry.nxHeartbeatMs > 0 && now - telemetry.nxHeartbeatMs < 3500;
        boolean stmFresh = telemetry.stm32Connected && telemetry.stm32DataMs > 0
                && now - telemetry.stm32DataMs < 3500;
        boolean linkHealthy = demo || nxFresh;

        status(c, 710, linkHealthy ? (demo ? AMBER : GREEN) : RED,
                demo ? "演示数据" : linkHealthy ? "遥控已连接" : "遥控未连接");
        drawBatteryStatus(c, 885, remoteBattery >= 0 ? remoteBattery : telemetry.remoteBattery);

        String vehicle;
        int vehicleColor;
        if (telemetry.vehicleState == TelemetrySnapshot.VehicleState.UNLOCKED) {
            vehicle = "车辆已解锁";
            vehicleColor = GREEN;
        } else if (telemetry.vehicleState == TelemetrySnapshot.VehicleState.LOCKED) {
            vehicle = "车辆未解锁";
            vehicleColor = AMBER;
        } else {
            vehicle = "车辆异常";
            vehicleColor = RED;
        }
        status(c, 1055, vehicleColor, vehicle);
        status(c, 1237, stmFresh || demo ? GREEN : RED,
                String.format(Locale.CHINA, "速度 %.2f m/s", telemetry.speed));

        boolean running = telemetry.measureState == TelemetrySnapshot.MeasureState.RUNNING;
        status(c, 1432, running ? GREEN : GRAY, running ? "测厚运行中" : "测厚已停止");
    }

    private void drawVideoPrimary(Canvas c) {
        RectF video = new RectF(0, HEADER_H, DESIGN_W, DESIGN_H);
        drawVideoSurface(c, video, false);

        RectF mini = new RectF(1190, 650, 1572, 968);
        drawThicknessCard(c, mini);
        register("swap_view", mini);
    }

    private void drawDataPrimary(Canvas c) {
        fill(c, PANEL, 0, HEADER_H, DESIGN_W, DESIGN_H, 0);

        text(c, "测厚数据", 34, 153, 30, TEXT, Paint.Align.LEFT, true);
        String value = String.format(Locale.CHINA, "%.1f", telemetry.thickness);
        text(c, value, 172, 176, 63, CYAN, Paint.Align.LEFT, false);
        text(c, "μm", 184 + measureText(value, 63, false), 176, 32, TEXT, Paint.Align.LEFT, false);
        text(c, telemetry.scanSide + " 扫", 43, 212, 21, MUTED, Paint.Align.LEFT, true);

        RectF chart = new RectF(100, 250, 1180, 875);
        drawChart(c, chart, waveform, false);
        RectF mini = new RectF(1190, 650, 1572, 968);
        drawVideoSurface(c, mini, true);
        register("swap_view", mini);
    }

    private void drawThicknessCard(Canvas c, RectF r) {
        boolean pressed = "swap_view".equals(pressedAction);
        fill(c, pressed ? Color.argb(247, 8, 28, 46) : Color.argb(235, 7, 25, 42),
                r.left, r.top, r.right, r.bottom, 20);
        stroke(c, pressed ? CYAN : Color.rgb(154, 185, 207),
                r.left, r.top, r.right, r.bottom, 20, pressed ? 2.5f : 1.5f);

        text(c, "测厚数据", r.left + 22, r.top + 42, 24, TEXT, Paint.Align.LEFT, true);
        text(c, telemetry.scanSide + " 扫", r.right - 22, r.top + 42, 18, MUTED, Paint.Align.RIGHT, true);

        String value = String.format(Locale.CHINA, "%.1f", telemetry.thickness);
        text(c, value, r.left + 24, r.top + 96, 48, CYAN, Paint.Align.LEFT, false);
        text(c, "μm", r.left + 34 + measureText(value, 48, false), r.top + 96,
                27, TEXT, Paint.Align.LEFT, false);

        RectF chart = new RectF(r.left + 60, r.top + 117, r.right - 18, r.bottom - 57);
        drawChart(c, chart, waveform, true);
        text(c, "⇄  点击切换", r.right - 18, r.bottom - 22, 17, TEXT, Paint.Align.RIGHT, true);
    }

    private void drawVideoSurface(Canvas c, RectF r, boolean compact) {
        float radius = compact ? 20 : 0;
        c.save();
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);

        fill(c, videoStreamAvailable ? Color.rgb(16, 29, 40) : Color.BLACK,
                r.left, r.top, r.right, r.bottom, radius);

        c.restore();
        if (compact) {
            boolean pressed = "swap_view".equals(pressedAction);
            stroke(c, pressed ? CYAN : Color.rgb(154, 185, 207),
                    r.left, r.top, r.right, r.bottom, radius, pressed ? 2.5f : 1.5f);
            text(c, "视频", r.left + 18, r.top + 35, 22, Color.WHITE, Paint.Align.LEFT, true);
            if (!videoStreamAvailable) {
                text(c, "等待视频流", r.centerX(), r.centerY() + 8, 19,
                        MUTED, Paint.Align.CENTER, false);
            }
            text(c, "⇄  点击切换", r.right - 18, r.bottom - 18, 17,
                    Color.WHITE, Paint.Align.RIGHT, true);
        } else {
            if (!videoStreamAvailable) {
                text(c, "等待视频流", r.centerX(), r.centerY() + 10, 29,
                        MUTED, Paint.Align.CENTER, false);
            }
        }
    }

    private void drawChart(Canvas c, RectF r, float[] values, boolean compact) {
        int divisionsX = compact ? 5 : 10;
        int divisionsY = compact ? 2 : 4;
        for (int i = 0; i <= divisionsX; i++) {
            float x = r.left + i * r.width() / divisionsX;
            c.drawLine(x, r.top, x, r.bottom, gridPaint);
            if (!compact) text(c, Integer.toString(i * 50), x, r.bottom + 32,
                    16, MUTED, Paint.Align.CENTER, false);
        }
        for (int i = 0; i <= divisionsY; i++) {
            float y = r.top + i * r.height() / divisionsY;
            c.drawLine(r.left, y, r.right, y, gridPaint);
            if (!compact) text(c, Integer.toString(140 - i * 5), r.left - 18, y + 6,
                    16, MUTED, Paint.Align.RIGHT, false);
        }
        paint.setColor(Color.rgb(119, 146, 166));
        paint.setStrokeWidth(1.3f);
        c.drawLine(r.left, r.top, r.left, r.bottom, paint);
        c.drawLine(r.left, r.bottom, r.right, r.bottom, paint);

        if (!compact) {
            text(c, "扫描距离 / mm", r.centerX(), r.bottom + 70,
                    19, TEXT, Paint.Align.CENTER, false);
            c.save();
            c.rotate(-90, r.left - 67, r.centerY());
            text(c, "厚度 / μm", r.left - 67, r.centerY() + 7,
                    19, TEXT, Paint.Align.CENTER, false);
            c.restore();
        }

        if (values == null || values.length < 2) {
            text(c, "等待测厚数据", r.centerX(), r.centerY(), compact ? 17 : 23,
                    MUTED, Paint.Align.CENTER, false);
            return;
        }

        Path path = new Path();
        for (int i = 0; i < values.length; i++) {
            float x = r.left + i * r.width() / (values.length - 1f);
            float normalized = (values[i] - 120f) / 20f;
            normalized = Math.max(0f, Math.min(1f, normalized));
            float y = r.bottom - normalized * r.height();
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(compact ? 2.2f : 3.2f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(CYAN);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void status(Canvas c, float x, int color, String label) {
        circle(c, color, x, 42, 7);
        text(c, label, x + 18, 51, 20, TEXT, Paint.Align.LEFT, false);
    }

    private void drawBatteryStatus(Canvas c, float x, int percent) {
        stroke(c, MUTED, x, 28, x + 31, 49, 4, 1.5f);
        fill(c, MUTED, x + 32, 34, x + 36, 43, 1);
        float width = 25f * Math.max(0, Math.min(100, percent)) / 100f;
        fill(c, percent < 20 ? RED : GREEN, x + 3, 31, x + 3 + width, 46, 2);
        text(c, "电量 " + percent + "%", x + 47, 51, 20, TEXT, Paint.Align.LEFT, false);
    }

    private void drawTransientMessage(Canvas c) {
        String message = communicationError != null ? "通信错误：" + communicationError : transientMessage;
        if (message == null) return;
        if (communicationError == null && SystemClock.uptimeMillis() >= transientMessageUntil) {
            transientMessage = null;
            return;
        }
        float width = Math.min(900f, Math.max(390f, message.length() * 25f));
        float left = (DESIGN_W - width) / 2f;
        fill(c, Color.argb(235, 4, 17, 29), left, 894, left + width, 958, 12);
        stroke(c, communicationError == null ? Color.rgb(85, 111, 132) : RED,
                left, 894, left + width, 958, 12, 1.5f);
        text(c, message, DESIGN_W / 2f, 935, 20, Color.WHITE, Paint.Align.CENTER, true);
    }

    private void register(String action, RectF area) { actions.put(action, new RectF(area)); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = (event.getX() - offsetX) / scale;
        float y = (event.getY() - offsetY) / scale;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownAt = SystemClock.uptimeMillis();
            pressedAction = actionAt(x, y);
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pressedAction = null;
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            String released = actionAt(x, y);
            String selected = pressedAction;
            pressedAction = null;
            if (selected != null && selected.equals(released)
                    && SystemClock.uptimeMillis() - touchDownAt < 1200) {
                performClick();
                if ("swap_view".equals(selected)) {
                    videoPrimary = !videoPrimary;
                    if (actionListener != null) {
                        actionListener.onAction(videoPrimary ? "video_primary" : "data_primary");
                    }
                }
            }
            invalidate();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private String actionAt(float x, float y) {
        for (Map.Entry<String, RectF> entry : actions.entrySet()) {
            if (entry.getValue().contains(x, y)) return entry.getKey();
        }
        return null;
    }

    private float measureText(String value, float size, boolean bold) {
        paint.setTextSize(size);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        return paint.measureText(value);
    }

    private void fill(Canvas c, int color, float l, float t, float r, float b, float radius) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, paint);
    }

    private void stroke(Canvas c, int color, float l, float t, float r, float b,
                        float radius, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void circle(Canvas c, int color, float x, float y, float radius) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
    }

    private void text(Canvas c, String value, float x, float baseline, float size,
                      int color, Paint.Align align, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        c.drawText(value, x, baseline, paint);
    }
}
