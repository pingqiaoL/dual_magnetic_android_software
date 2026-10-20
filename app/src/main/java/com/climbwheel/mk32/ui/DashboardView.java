package com.climbwheel.mk32.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.climbwheel.mk32.model.TelemetrySnapshot;
import com.climbwheel.mk32.model.SavedMeasurement;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;

/**
 * Minimal 16:10 picture-in-picture dashboard for the MK32 1280x800 display.
 * The default view is full-screen video with a thickness card. Tapping the card
 * swaps the video and thickness panes without stopping telemetry processing.
 */
public final class DashboardView extends View {
    public interface ActionListener { void onAction(String action); }
    private enum StatusTone { GOOD, WARNING, BAD, NEUTRAL }

    private static final float DESIGN_W = 1600f;
    private static final float DESIGN_H = 1000f;
    private static final float HEADER_H = 82f;
    private static final long THICKNESS_HOLD_MS = ThicknessPlot.HOLD_MS;
    private long chartWindowMs = ChartZoom.DEFAULT_MS;
    private static final long COMPACT_CHART_WINDOW_MS = 10_000;

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
    private static final int LIGHT_BACKGROUND = Color.rgb(243, 247, 249);
    private static final int LIGHT_CARD = Color.rgb(253, 254, 255);
    private static final int LIGHT_TEXT = Color.rgb(25, 42, 55);
    private static final int LIGHT_MUTED = Color.rgb(91, 111, 127);
    private static final int LIGHT_BORDER = Color.rgb(209, 220, 228);
    private static final int LIGHT_GRID = Color.rgb(211, 222, 229);
    private static final int TEAL = Color.rgb(0, 168, 184);
    private static final int LIGHT_GREEN = Color.rgb(32, 181, 88);
    private static final int LIGHT_AMBER = Color.rgb(239, 139, 16);
    private static final int LIGHT_RED = Color.rgb(218, 57, 70);
    private static final int LIGHT_GRAY = Color.rgb(143, 157, 168);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, RectF> actions = new HashMap<>();
    private ThicknessHistory thicknessHistory = new ThicknessHistory();
    private boolean savedDataView;
    private final SimpleDateFormat headerClock = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA);
    private final RectF chartTouchArea = new RectF();

    private TelemetrySnapshot telemetry = TelemetrySnapshot.builder().build();
    private ActionListener actionListener;
    private String communicationError;
    private boolean localRecording;
    private boolean videoPrimary = true;
    private boolean videoStreamAvailable;
    private int remoteBattery = -1;
    private String transientMessage;
    private long transientMessageUntil;
    private long lastThicknessDataMs;
    private TelemetrySnapshot.MeasureState previousMeasureState = TelemetrySnapshot.MeasureState.STOPPED;
    private boolean roundRunning;
    private long roundStartMs;
    private long roundStopMs;
    private boolean chartLive = true;
    private long historicalChartEndMs;
    private long selectedSampleMs;
    private boolean chartGesture;
    private boolean chartMoved;
    private float chartDownX;
    private long chartDragStartEndMs;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;
    private long touchDownAt;
    private String pressedAction;
    private final ScaleGestureDetector scaleDetector;
    private boolean scalingGesture;

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 250);
        }
    };

    public DashboardView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        // CLEAR must operate on this view's content, including the white data-page background.
        // A separate empty saveLayer inside clear() cannot erase that background.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(1f);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        float x = (detector.getFocusX() - offsetX) / scale;
                        float y = (detector.getFocusY() - offsetY) / scale;
                        if (videoPrimary || !chartTouchArea.contains(x, y)) return false;
                        scalingGesture = true;
                        chartMoved = true;
                        pressedAction = null;
                        return true;
                    }

                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float x = (detector.getFocusX() - offsetX) / scale;
                        zoomChart(detector.getScaleFactor(),
                                (x - chartTouchArea.left) / chartTouchArea.width());
                        return true;
                    }
                });
    }

    public void setActionListener(ActionListener listener) { actionListener = listener; }

    /** A separate read-only view: live reception and recording remain in the underlying activity. */
    public void setSavedMeasurement(SavedMeasurement saved, TelemetrySnapshot current) {
        savedDataView = true;
        videoPrimary = false;
        telemetry = current == null ? TelemetrySnapshot.builder().build() : current;
        thicknessHistory = new ThicknessHistory(false);
        for (int i = 0; i < saved.timestamps.length; i++) {
            thicknessHistory.add(saved.timestamps[i], saved.values[i]);
        }
        roundStartMs = saved.startMs;
        roundStopMs = saved.endMs;
        roundRunning = false;
        chartLive = false;
        historicalChartEndMs = roundStartMs + chartWindowMs;
        invalidate();
    }

    public void updateDeviceStatus(TelemetrySnapshot value) {
        telemetry = value;
        invalidate();
    }

    public void setTelemetry(TelemetrySnapshot value) {
        telemetry = value;
        communicationError = null;
        if (value.measureState != previousMeasureState) {
            if (value.measureState == TelemetrySnapshot.MeasureState.RUNNING) {
                beginMeasurementRound(value.timestampMs);
            } else {
                finishMeasurementRound(value.timestampMs);
            }
            previousMeasureState = value.measureState;
        }
        if (roundRunning && value.thicknessDataMs > lastThicknessDataMs
                && thicknessHistory.add(value.thicknessDataMs, value.thickness)) {
            lastThicknessDataMs = value.thicknessDataMs;
            if (chartLive) selectedSampleMs = 0;
        } else if (value.thicknessDataMs > lastThicknessDataMs) {
            // Consume out-of-round messages so a later heartbeat cannot add them to a new round.
            lastThicknessDataMs = value.thicknessDataMs;
        }
        postInvalidateOnAnimation();
    }

    private void beginMeasurementRound(long timestampMs) {
        thicknessHistory.clear();
        roundRunning = true;
        roundStartMs = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        roundStopMs = 0;
        chartLive = true;
        historicalChartEndMs = 0;
        selectedSampleMs = 0;
    }

    private void finishMeasurementRound(long timestampMs) {
        if (roundStartMs == 0) return;
        roundRunning = false;
        roundStopMs = Math.max(roundStartMs, timestampMs);
        chartLive = true;
        historicalChartEndMs = 0;
        selectedSampleMs = 0;
    }

    private void clearDisplayedRound() {
        thicknessHistory.clear();
        selectedSampleMs = 0;
        chartLive = true;
        historicalChartEndMs = 0;
        if (!roundRunning) {
            roundStartMs = 0;
            roundStopMs = 0;
        }
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

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
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
        boolean light = !videoPrimary;
        int headerBackground = light ? LIGHT_CARD : HEADER;
        int headerText = light ? LIGHT_TEXT : Color.WHITE;
        int headerMuted = light ? LIGHT_MUTED : MUTED;
        fill(c, headerBackground, 0, 0, DESIGN_W, HEADER_H, 0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(light ? LIGHT_BORDER : Color.rgb(55, 77, 96));
        paint.setStrokeWidth(1.2f);
        c.drawLine(0, HEADER_H, DESIGN_W, HEADER_H, paint);
        text(c, "磁轮检测机器人", 24, 53, 29, headerText, Paint.Align.LEFT, true);
        text(c, headerClock.format(new Date()), 250, 51, 18, headerMuted, Paint.Align.LEFT, false);

        long now = System.currentTimeMillis();
        boolean demo = telemetry.linkState == TelemetrySnapshot.LinkState.DEMO;
        boolean nxFresh = telemetry.nxHeartbeatMs > 0 && now - telemetry.nxHeartbeatMs < 3500;
        boolean stmFresh = telemetry.stm32Connected && telemetry.stm32DataMs > 0
                && now - telemetry.stm32DataMs < 3500;
        boolean linkHealthy = demo || nxFresh;

        status(c, 710, statusColor(demo ? StatusTone.WARNING
                        : linkHealthy ? StatusTone.GOOD : StatusTone.BAD, light),
                demo ? "演示数据" : linkHealthy ? "遥控已连接" : "遥控未连接");
        drawBatteryStatus(c, 885, remoteBattery >= 0 ? remoteBattery : telemetry.remoteBattery, light);

        String vehicle;
        int vehicleColor;
        if (telemetry.vehicleState == TelemetrySnapshot.VehicleState.UNLOCKED) {
            vehicle = "车辆已解锁";
            vehicleColor = statusColor(StatusTone.GOOD, light);
        } else if (telemetry.vehicleState == TelemetrySnapshot.VehicleState.LOCKED) {
            vehicle = "车辆未解锁";
            vehicleColor = statusColor(StatusTone.WARNING, light);
        } else {
            vehicle = "车辆异常";
            vehicleColor = statusColor(StatusTone.BAD, light);
        }
        status(c, 1055, vehicleColor, vehicle);
        status(c, 1237, statusColor(stmFresh || demo ? StatusTone.GOOD : StatusTone.BAD, light),
                String.format(Locale.CHINA, "速度 %.2f m/s", telemetry.speed));

        boolean running = telemetry.measureState == TelemetrySnapshot.MeasureState.RUNNING;
        status(c, 1432, statusColor(running ? StatusTone.GOOD : StatusTone.NEUTRAL, light),
                running ? "测厚运行中" : "测厚已停止");
    }

    private void drawVideoPrimary(Canvas c) {
        RectF video = new RectF(0, HEADER_H, DESIGN_W, DESIGN_H);
        drawVideoSurface(c, video, false);

        RectF mini = new RectF(1190, 650, 1572, 968);
        drawThicknessCard(c, mini);
        register("swap_view", mini);
    }

    private void drawDataPrimary(Canvas c) {
        fill(c, LIGHT_BACKGROUND, 0, HEADER_H, DESIGN_W, DESIGN_H, 0);
        fill(c, LIGHT_CARD, 18, 103, 1582, 976, 18);
        stroke(c, LIGHT_BORDER, 18, 103, 1582, 976, 18, 1.2f);

        text(c, savedDataView ? "历史数据" : "测厚数据", 38, 157, 30, LIGHT_TEXT, Paint.Align.LEFT, true);
        long now = System.currentTimeMillis();
        boolean fresh = isThicknessFresh(now);
        String value = String.format(Locale.CHINA, "%.1f", displayedThickness(now));
        text(c, value, 176, 180, 63, TEAL, Paint.Align.LEFT, false);
        text(c, "μm", 188 + measureText(value, 63, false), 180, 32, LIGHT_TEXT, Paint.Align.LEFT, false);
        long endMs = chartEndMs(now);
        ThicknessHistory.Stats stats = thicknessHistory.stats(endMs - chartWindowMs, endMs);
        String summary = stats.count == 0 ? ""
                : String.format(Locale.CHINA, "点数 %d    最小 %.1f    最大 %.1f    平均 %.1f μm",
                stats.count, stats.minimum, stats.maximum, stats.average);
        text(c, summary, 500, 169, 19, LIGHT_MUTED, Paint.Align.LEFT, false);
        text(c, formatElapsed(roundElapsedMs(now)), 1165, 169, 21,
                roundRunning ? LIGHT_GREEN : LIGHT_MUTED, Paint.Align.RIGHT, true);

        RectF history = new RectF(1220, 125, 1542, 190);
        fill(c, Color.rgb(236, 248, 249), history.left, history.top, history.right, history.bottom, 12);
        stroke(c, TEAL, history.left, history.top, history.right, history.bottom, 12, 1.4f);
        text(c, savedDataView ? "返回测量" : "历史数据", history.centerX(), 167, 24,
                LIGHT_TEXT, Paint.Align.CENTER, true);
        register(savedDataView ? "history_close" : "history_open", history);

        chartButton(c, "chart_zoom_in", "放大 ＋", 100, 250);
        chartButton(c, "chart_zoom_out", "缩小 −", 265, 415);
        chartButton(c, "chart_zoom_reset", "复位", 430, 550);
        text(c, String.format(Locale.CHINA, "%.1f 秒/屏", chartWindowMs / 1000f),
                570, 217, 18, LIGHT_MUTED, Paint.Align.LEFT, false);

        if (!savedDataView) {
            RectF clear = new RectF(995, 187, 1165, 232);
            fill(c, Color.rgb(249, 250, 251), clear.left, clear.top, clear.right, clear.bottom, 10);
            stroke(c, LIGHT_AMBER, clear.left, clear.top, clear.right, clear.bottom, 10, 1.4f);
            text(c, "清空数据", clear.centerX(), 217, 18, LIGHT_TEXT, Paint.Align.CENTER, true);
            register("chart_clear", clear);
        }
        if (!chartLive && !savedDataView) {
            RectF live = new RectF(810, 187, 980, 232);
            fill(c, Color.rgb(240, 250, 251), live.left, live.top, live.right, live.bottom, 10);
            stroke(c, TEAL, live.left, live.top, live.right, live.bottom, 10, 1.2f);
            text(c, "回到实时", live.centerX(), 217, 18, LIGHT_TEXT, Paint.Align.CENTER, true);
            register("chart_live", live);
        }

        RectF chart = new RectF(100, 250, 1180, 875);
        chartTouchArea.set(chart);
        drawTimeSeriesChart(c, chart, false, now);
        RectF mini = new RectF(1190, 650, 1572, 968);
        drawVideoSurface(c, mini, true);
        if (!savedDataView) register("swap_view", mini);
    }

    private void drawThicknessCard(Canvas c, RectF r) {
        boolean pressed = "swap_view".equals(pressedAction);
        fill(c, pressed ? Color.rgb(238, 249, 250) : LIGHT_CARD,
                r.left, r.top, r.right, r.bottom, 20);
        stroke(c, pressed ? TEAL : LIGHT_BORDER,
                r.left, r.top, r.right, r.bottom, 20, pressed ? 2.5f : 1.5f);

        String value = String.format(Locale.CHINA, "%.1f", displayedThickness(System.currentTimeMillis()));
        float size = Math.min(126f, r.height() * .43f);
        float unitSize = size * .40f;
        float gap = 12f;
        float width = measureText(value, size, true) + gap + measureText("μm", unitSize, false);
        if (width > r.width() - 32f) {
            float ratio = (r.width() - 32f) / width;
            size *= ratio;
            unitSize *= ratio;
            width = measureText(value, size, true) + gap + measureText("μm", unitSize, false);
        }
        float left = r.centerX() - width / 2f;
        float baseline = r.centerY() + size * .34f;
        text(c, value, left, baseline, size, LIGHT_TEXT, Paint.Align.LEFT, true);
        text(c, "μm", left + measureText(value, size, true) + gap,
                baseline, unitSize, LIGHT_TEXT, Paint.Align.LEFT, false);
    }

    private void drawVideoSurface(Canvas c, RectF r, boolean compact) {
        float radius = compact ? 20 : 0;
        if (videoStreamAvailable) clear(c, r, radius);
        else fill(c, Color.BLACK, r.left, r.top, r.right, r.bottom, radius);
        if (compact) {
            boolean pressed = "swap_view".equals(pressedAction);
            stroke(c, pressed ? CYAN : Color.rgb(154, 185, 207),
                    r.left, r.top, r.right, r.bottom, radius, pressed ? 2.5f : 1.5f);
            text(c, "视频", r.left + 18, r.top + 35, 22, Color.WHITE, Paint.Align.LEFT, true);
            if (!videoStreamAvailable) {
                text(c, "等待视频流", r.centerX(), r.centerY() + 8, 19,
                        MUTED, Paint.Align.CENTER, false);
            }
        } else if (!videoStreamAvailable) {
            text(c, "等待视频流", r.centerX(), r.centerY() + 10, 29,
                    MUTED, Paint.Align.CENTER, false);
        }
    }

    private void drawTimeSeriesChart(Canvas c, RectF r, boolean compact, long nowMs) {
        gridPaint.setColor(LIGHT_GRID);
        long windowMs = compact ? COMPACT_CHART_WINDOW_MS : chartWindowMs;
        long endMs = compact ? liveChartEnd(nowMs, COMPACT_CHART_WINDOW_MS) : chartEndMs(nowMs);
        long startMs = endMs - windowMs;
        ThicknessHistory.Stats stats = thicknessHistory.stats(startMs, endMs);
        long anchor = roundRunning ? nowMs : Math.max(roundStartMs, roundStopMs);
        List<ThicknessPlot.Point> visible = ThicknessPlot.build(thicknessHistory.samples(),
                roundStartMs > 0 ? Math.max(roundStartMs, startMs) : startMs,
                Math.min(anchor, endMs));

        float minY;
        float maxY;
        if (stats.count == 0) {
            minY = 0f;
            maxY = 10f;
        } else {
            // Keep a real zero baseline visible when a reading expires.
            minY = Math.min(0f, stats.minimum);
            maxY = stats.maximum + Math.max(.5f, Math.abs(stats.maximum) * .15f);
            if (maxY - minY < 1f) maxY = minY + 1f;
        }
        // Include a still-held sample just outside the viewport before drawing axis ticks.
        for (ThicknessPlot.Point point : visible) {
            minY = Math.min(minY, point.value);
            maxY = Math.max(maxY, point.value + Math.max(.5f, Math.abs(point.value) * .15f));
        }

        int divisionsX = compact ? 3 : 5;
        int divisionsY = compact ? 2 : 5;
        for (int i = 0; i <= divisionsX; i++) {
            float fraction = i / (float) divisionsX;
            float x = r.left + fraction * r.width();
            c.drawLine(x, r.top, x, r.bottom, gridPaint);
            if (!compact) {
                long tickMs = startMs + Math.round(fraction * windowMs);
                Paint.Align align = i == 0 ? Paint.Align.LEFT
                        : i == divisionsX ? Paint.Align.RIGHT : Paint.Align.CENTER;
                long originMs = roundStartMs > 0 ? roundStartMs : startMs;
                text(c, formatElapsed(Math.max(0, tickMs - originMs)), x, r.bottom + 31,
                        15, LIGHT_MUTED, align, false);
            }
        }
        for (int i = 0; i <= divisionsY; i++) {
            float fraction = i / (float) divisionsY;
            float y = r.top + fraction * r.height();
            c.drawLine(r.left, y, r.right, y, gridPaint);
            if (!compact) {
                float tick = maxY - fraction * (maxY - minY);
                text(c, String.format(Locale.CHINA, "%.1f", tick), r.left - 18, y + 6,
                        15, LIGHT_MUTED, Paint.Align.RIGHT, false);
            }
        }
        paint.setColor(Color.rgb(132, 151, 164));
        paint.setStrokeWidth(1.3f);
        c.drawLine(r.left, r.top, r.left, r.bottom, paint);
        c.drawLine(r.left, r.bottom, r.right, r.bottom, paint);

        if (!compact) {
            c.save();
            c.rotate(-90, r.left - 68, r.centerY());
            text(c, "厚度 / μm", r.left - 68, r.centerY() + 7,
                    18, LIGHT_TEXT, Paint.Align.CENTER, false);
            c.restore();
        }

        if (roundStartMs <= 0) return;
        float[] xs = new float[visible.size()];
        float[] ys = new float[visible.size()];
        float[] slopes = new float[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            ThicknessPlot.Point sample = visible.get(i);
            xs[i] = r.left + (sample.timeMs - startMs) * r.width() / windowMs;
            ys[i] = valueY(sample.value, minY, maxY, r);
        }
        for (int i = 0; i < visible.size(); i++) {
            boolean before = i > 0;
            boolean after = i + 1 < visible.size();
            if (before && after) slopes[i] = SmoothCurve.tangent(
                    xs[i - 1], ys[i - 1], xs[i], ys[i], xs[i + 1], ys[i + 1]);
            else if (before && xs[i] > xs[i - 1])
                slopes[i] = (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1]);
            else if (after && xs[i + 1] > xs[i])
                slopes[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        }
        Path path = new Path();
        for (int i = 0; i < visible.size(); i++) {
            if (i == 0) path.moveTo(xs[i], ys[i]);
            else {
                float third = (xs[i] - xs[i - 1]) / 3f;
                path.cubicTo(xs[i - 1] + third, ys[i - 1] + slopes[i - 1] * third,
                        xs[i] - third, ys[i] - slopes[i] * third, xs[i], ys[i]);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(compact ? 2.2f : 3.0f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(TEAL);
        int chartClip = c.save();
        c.clipRect(r);
        c.drawPath(path, paint);
        c.restoreToCount(chartClip);
        paint.setStyle(Paint.Style.FILL);

        float lastLabelRight = -Float.MAX_VALUE;
        for (int i = 0; i < visible.size(); i++) {
            if (!visible.get(i).measured || xs[i] < r.left || xs[i] > r.right) continue;
            circle(c, Color.WHITE, xs[i], ys[i], 6f);
            circle(c, LIGHT_RED, xs[i], ys[i], 4.5f);
            String label = String.format(Locale.CHINA, "%.1f", visible.get(i).value);
            float half = measureText(label, 18f, true) / 2f;
            float labelX = Math.max(r.left + half, Math.min(r.right - half, xs[i]));
            // Keep each label immediately above its own sample, never stack it over another dot.
            if (labelX - half < lastLabelRight + 6f) continue;
            float baseline = Math.max(r.top + 24f, ys[i] - 18f);
            fill(c, Color.argb(235, 253, 254, 255), labelX - half - 4f,
                    baseline - 22f, labelX + half + 4f, baseline + 3f, 4f);
            paint.setColor(LIGHT_RED);
            paint.setStrokeWidth(1f);
            c.drawLine(xs[i], ys[i] - 7f, labelX, baseline + 5f, paint);
            text(c, label, labelX, baseline, 18f, LIGHT_RED, Paint.Align.CENTER, true);
            lastLabelRight = labelX + half;
        }

        if (!compact && selectedSampleMs > 0) {
            ThicknessHistory.Sample selected = thicknessHistory.nearest(
                    selectedSampleMs, startMs, endMs);
            if (selected != null) drawSelectedSample(c, r, selected, startMs, windowMs, minY, maxY);
        }
    }

    private void drawSelectedSample(Canvas c, RectF r, ThicknessHistory.Sample sample,
                                    long startMs, long windowMs, float minY, float maxY) {
        float x = r.left + (sample.timestampMs - startMs) * r.width() / windowMs;
        float y = valueY(sample.value, minY, maxY, r);
        paint.setColor(Color.argb(150, 130, 208, 226));
        paint.setStrokeWidth(1.2f);
        c.drawLine(x, r.top, x, r.bottom, paint);
        circle(c, Color.WHITE, x, y, 8f);
        circle(c, LIGHT_RED, x, y, 5f);

        String label = formatElapsed(Math.max(0, sample.timestampMs - roundStartMs)) + "   "
                + String.format(Locale.CHINA, "%.1f μm", sample.value);
        float boxWidth = Math.max(205f, measureText(label, 17, true) + 30f);
        float left = Math.max(r.left + 6, Math.min(x - boxWidth / 2f, r.right - boxWidth - 6));
        float top = Math.max(r.top + 8, y - 62);
        fill(c, Color.argb(245, 10, 37, 57), left, top, left + boxWidth, top + 43, 9);
        stroke(c, TEAL, left, top, left + boxWidth, top + 43, 9, 1.2f);
        text(c, label, left + boxWidth / 2f, top + 28, 17, TEXT, Paint.Align.CENTER, true);
    }

    private static float valueY(float value, float minimum, float maximum, RectF area) {
        float normalized = (value - minimum) / (maximum - minimum);
        normalized = Math.max(0f, Math.min(1f, normalized));
        return area.bottom - normalized * area.height();
    }

    private boolean isThicknessFresh(long nowMs) {
        return roundRunning && lastThicknessDataMs > 0
                && nowMs >= lastThicknessDataMs
                && nowMs - lastThicknessDataMs < THICKNESS_HOLD_MS;
    }

    private float displayedThickness(long nowMs) {
        if (savedDataView) {
            long end = chartEndMs(nowMs);
            ThicknessHistory.Sample selected = thicknessHistory.nearest(
                    selectedSampleMs > 0 ? selectedSampleMs : end,
                    end - chartWindowMs, end);
            return selected == null ? 0f : selected.value;
        }
        return isThicknessFresh(nowMs) ? telemetry.thickness : 0f;
    }

    private long chartEndMs(long nowMs) {
        if (chartLive || historicalChartEndMs <= 0) return liveChartEnd(nowMs, chartWindowMs);
        return clampHistoricalEnd(historicalChartEndMs, nowMs);
    }

    private long clampHistoricalEnd(long requested, long nowMs) {
        long maximumEnd = liveChartEnd(nowMs, chartWindowMs);
        long earliest = roundStartMs > 0 ? roundStartMs : thicknessHistory.firstTimestampMs();
        long minimumEnd = earliest > 0 ? earliest + chartWindowMs : maximumEnd;
        if (minimumEnd > maximumEnd) minimumEnd = maximumEnd;
        return Math.max(minimumEnd, Math.min(maximumEnd, requested));
    }

    private long liveChartEnd(long nowMs, long windowMs) {
        if (roundStartMs <= 0) return nowMs;
        long anchor = roundRunning ? nowMs : Math.max(roundStartMs, roundStopMs);
        return Math.max(roundStartMs + windowMs, anchor);
    }

    private long roundElapsedMs(long nowMs) {
        if (roundStartMs <= 0) return 0;
        long end = roundRunning ? nowMs : Math.max(roundStartMs, roundStopMs);
        return Math.max(0, end - roundStartMs);
    }

    private static String formatElapsed(long elapsedMs) {
        long totalTenths = Math.max(0, elapsedMs) / 100;
        long hours = totalTenths / 36_000;
        long minutes = (totalTenths / 600) % 60;
        long seconds = (totalTenths / 10) % 60;
        long tenths = totalTenths % 10;
        if (hours > 0) {
            return String.format(Locale.CHINA, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths);
        }
        return String.format(Locale.CHINA, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    private void status(Canvas c, float x, int color, String label) {
        circle(c, color, x, 42, 7);
        text(c, label, x + 18, 51, 20,
                videoPrimary ? TEXT : LIGHT_TEXT, Paint.Align.LEFT, false);
    }

    private static int statusColor(StatusTone tone, boolean light) {
        if (!light) {
            if (tone == StatusTone.GOOD) return GREEN;
            if (tone == StatusTone.WARNING) return AMBER;
            if (tone == StatusTone.BAD) return RED;
            return GRAY;
        }
        if (tone == StatusTone.GOOD) return LIGHT_GREEN;
        if (tone == StatusTone.WARNING) return LIGHT_AMBER;
        if (tone == StatusTone.BAD) return LIGHT_RED;
        return LIGHT_GRAY;
    }

    private void drawBatteryStatus(Canvas c, float x, int percent, boolean light) {
        int muted = light ? LIGHT_MUTED : MUTED;
        stroke(c, muted, x, 28, x + 31, 49, 4, 1.5f);
        fill(c, muted, x + 32, 34, x + 36, 43, 1);
        float width = 25f * Math.max(0, Math.min(100, percent)) / 100f;
        fill(c, statusColor(percent < 20 ? StatusTone.BAD : StatusTone.GOOD, light),
                x + 3, 31, x + 3 + width, 46, 2);
        text(c, "电量 " + percent + "%", x + 47, 51, 20,
                light ? LIGHT_TEXT : TEXT, Paint.Align.LEFT, false);
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

    private void chartButton(Canvas c, String action, String label, float left, float right) {
        RectF area = new RectF(left, 187, right, 232);
        fill(c, Color.rgb(240, 250, 251), left, area.top, right, area.bottom, 10);
        stroke(c, LIGHT_BORDER, left, area.top, right, area.bottom, 10, 1.2f);
        text(c, label, area.centerX(), 217, 18, LIGHT_TEXT, Paint.Align.CENTER, true);
        register(action, area);
    }

    private void zoomChart(float factor, float focus) {
        long now = System.currentTimeMillis();
        long oldEnd = chartEndMs(now);
        long oldWindow = chartWindowMs;
        chartWindowMs = ChartZoom.scale(oldWindow, factor);
        if (!chartLive) historicalChartEndMs = clampHistoricalEnd(
                ChartZoom.anchoredEnd(oldEnd, oldWindow, chartWindowMs, focus), now);
        selectedSampleMs = 0;
        postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (scalingGesture) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                scalingGesture = false;
                chartGesture = false;
                chartMoved = false;
                pressedAction = null;
            }
            return true;
        }
        float x = (event.getX() - offsetX) / scale;
        float y = (event.getY() - offsetY) / scale;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownAt = SystemClock.uptimeMillis();
            if (!videoPrimary && chartTouchArea.contains(x, y)) {
                chartGesture = true;
                chartMoved = false;
                chartDownX = x;
                chartDragStartEndMs = chartEndMs(System.currentTimeMillis());
                pressedAction = null;
                return true;
            }
            pressedAction = actionAt(x, y);
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && chartGesture) {
            float deltaX = x - chartDownX;
            if (Math.abs(deltaX) > 8f) chartMoved = true;
            if (chartMoved) {
                long now = System.currentTimeMillis();
                long requested = chartDragStartEndMs
                        - Math.round(deltaX * chartWindowMs / chartTouchArea.width());
                historicalChartEndMs = clampHistoricalEnd(requested, now);
                chartLive = historicalChartEndMs >= liveChartEnd(now, chartWindowMs)
                        - Math.min(500, chartWindowMs / 20);
                if (chartLive) historicalChartEndMs = 0;
                selectedSampleMs = 0;
                invalidate();
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            chartGesture = false;
            chartMoved = false;
            pressedAction = null;
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (chartGesture) {
                if (!chartMoved) {
                    long endMs = chartEndMs(System.currentTimeMillis());
                    long startMs = endMs - chartWindowMs;
                    float fraction = Math.max(0f, Math.min(1f,
                            (x - chartTouchArea.left) / chartTouchArea.width()));
                    long target = startMs + Math.round(fraction * chartWindowMs);
                    ThicknessHistory.Sample nearest = thicknessHistory.nearest(target, startMs, endMs);
                    selectedSampleMs = nearest == null ? 0 : nearest.timestampMs;
                    performClick();
                }
                chartGesture = false;
                chartMoved = false;
                invalidate();
                return true;
            }
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
                } else if ("chart_live".equals(selected)) {
                    chartLive = true;
                    historicalChartEndMs = 0;
                    selectedSampleMs = 0;
                } else if ("chart_clear".equals(selected)) {
                    clearDisplayedRound();
                } else if ("chart_zoom_in".equals(selected)) {
                    zoomChart(2f, .5f);
                } else if ("chart_zoom_out".equals(selected)) {
                    zoomChart(.5f, .5f);
                } else if ("chart_zoom_reset".equals(selected)) {
                    zoomChart(chartWindowMs / (float) ChartZoom.DEFAULT_MS, .5f);
                } else if (actionListener != null) {
                    actionListener.onAction(selected);
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

    private void clear(Canvas c, RectF area, float radius) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRoundRect(area, radius, radius, paint);
        paint.setXfermode(null);
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
