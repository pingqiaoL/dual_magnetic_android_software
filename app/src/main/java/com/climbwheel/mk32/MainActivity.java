package com.climbwheel.mk32;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.Color;
import android.widget.FrameLayout;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.climbwheel.mk32.model.TelemetrySnapshot;
import com.climbwheel.mk32.model.SavedMeasurement;
import com.climbwheel.mk32.telemetry.DemoTelemetrySource;
import com.climbwheel.mk32.telemetry.MavlinkUartSource;
import com.climbwheel.mk32.telemetry.TelemetrySource;
import com.climbwheel.mk32.ui.DashboardView;
import com.climbwheel.mk32.video.RtspVideoController;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TelemetrySource.Listener, DashboardView.ActionListener {
    private static final String RTSP_URL = "rtsp://192.168.144.129:8554/live";
    private static final float DESIGN_WIDTH = 1600f;
    private static final float DESIGN_HEIGHT = 1000f;
    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private DashboardView dashboard;
    private FrameLayout rootView;
    private SurfaceView videoSurface;
    private RtspVideoController videoController;
    private boolean videoPrimary = true;
    private TelemetrySource telemetrySource;
    private final Deque<String> recordedRows = new ArrayDeque<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat fileClock = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA);
    private Dialog historyDialog;
    private DashboardView historyDashboard;
    private boolean localRecording;
    private long lastRecordedThicknessMs;
    private long recordingRoundStartMs;
    private TelemetrySnapshot.MeasureState lastMeasurementState = TelemetrySnapshot.MeasureState.STOPPED;
    private int markCount;
    private TelemetrySnapshot latestSnapshot;
    private File lastSavedFile;
    private int cachedRemoteBattery = -1;
    private long batteryReadAt;
    private final Runnable batteryUpdater = new Runnable() {
        @Override public void run() {
            if (dashboard == null) return;
            dashboard.setRemoteBattery(readRemoteBatteryPercent());
            dashboard.postDelayed(this, 30000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUi();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            boolean barsVisible = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                    || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
            if (barsVisible) getWindow().getDecorView().postDelayed(this::hideSystemUi, 120);
        });

        dashboard = new DashboardView(this);
        dashboard.setActionListener(this);
        dashboard.setLocalRecording(false);
        dashboard.setRemoteBattery(readRemoteBatteryPercent());
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);
        // SurfaceView uses the device's native video overlay path.  It is more reliable than
        // TextureView for the legacy Qualcomm H.264 decoder used by the MK32 firmware.
        videoSurface = new SurfaceView(this);
        rootView.addView(videoSurface, new FrameLayout.LayoutParams(1, 1));
        rootView.addView(dashboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootView);
        String videoUrl = RTSP_URL;
        boolean videoCatchUpEnabled = true;
        if (BuildConfig.DEBUG) {
            String testUrl = getIntent().getStringExtra("video_test_url");
            if (testUrl != null) {
                Uri testUri = Uri.parse(testUrl);
                if ("rtsp".equals(testUri.getScheme())
                        && "127.0.0.1".equals(testUri.getHost()) && testUri.getPort() > 0) {
                    videoUrl = testUrl;
                    videoCatchUpEnabled = !getIntent()
                            .getBooleanExtra("video_test_disable_catch_up", false);
                }
            }
        }
        videoController = new RtspVideoController(this, videoSurface, videoUrl,
                dashboard::setVideoStreamAvailable, videoCatchUpEnabled);
        if (BuildConfig.DEBUG) {
            videoController.setInitialTestPauseMs(getIntent().getIntExtra("video_test_pause_ms", 0));
        }
        rootView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateVideoLayout();
            }
        });
        rootView.post(this::updateVideoLayout);

        boolean demoWaveTest = BuildConfig.DEBUG
                && getIntent().getBooleanExtra("demo_wave_test", false);
        MavlinkUartSource uart = new MavlinkUartSource(MavlinkUartSource.DEFAULT_DEVICE);
        telemetrySource = demoWaveTest ? new DemoTelemetrySource(true)
                : uart.exists() ? uart : new DemoTelemetrySource();
        if (demoWaveTest) dashboard.showMessage("虚拟波形验证：将自动执行两轮测厚");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
    }

    private void refreshSystemUi() {
        hideSystemUi();
        getWindow().getDecorView().postDelayed(this::hideSystemUi, 300);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSystemUi();
    }

    @Override protected void onStart() {
        super.onStart();
        dashboard.removeCallbacks(batteryUpdater);
        dashboard.post(batteryUpdater);
        telemetrySource.start(this);
        videoController.start();
    }

    @Override protected void onStop() {
        dashboard.removeCallbacks(batteryUpdater);
        videoController.stop();
        telemetrySource.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (videoController != null) videoController.release();
        if (historyDialog != null) historyDialog.dismiss();
        fileExecutor.shutdown();
        super.onDestroy();
    }

    @Override public void onTelemetry(TelemetrySnapshot snapshot) {
        int remoteBattery = readRemoteBatteryPercent();
        TelemetrySnapshot displayed = remoteBattery >= 0
                ? snapshot.buildUpon().remoteBattery(remoteBattery).build() : snapshot;
        runOnUiThread(() -> {
            latestSnapshot = displayed;
            boolean started = lastMeasurementState != TelemetrySnapshot.MeasureState.RUNNING
                    && displayed.measureState == TelemetrySnapshot.MeasureState.RUNNING;
            boolean stopped = lastMeasurementState == TelemetrySnapshot.MeasureState.RUNNING
                    && displayed.measureState != TelemetrySnapshot.MeasureState.RUNNING;

            if (started) {
                recordedRows.clear();
                markCount = 0;
                lastRecordedThicknessMs = 0;
                recordingRoundStartMs = displayed.timestampMs;
                localRecording = true;
                dashboard.setLocalRecording(true);
            }
            if (localRecording
                    && !Float.isNaN(displayed.thickness) && !Float.isInfinite(displayed.thickness)
                    && displayed.thicknessDataMs >= recordingRoundStartMs
                    && displayed.thicknessDataMs > lastRecordedThicknessMs) {
                lastRecordedThicknessMs = displayed.thicknessDataMs;
                appendSample(displayed, "DATA");
            }
            dashboard.setTelemetry(displayed);
            if (historyDashboard != null) historyDashboard.updateDeviceStatus(displayed);
            if (stopped) {
                localRecording = false;
                dashboard.setLocalRecording(false);
                if (recordedRows.isEmpty()) {
                    dashboard.showMessage("本轮测厚已结束，没有有效数据");
                } else {
                    saveCsv();
                }
            }
            lastMeasurementState = displayed.measureState;
        });
    }

    /** MK32 firmware reports 0 through Android battery APIs; the kernel capacity is valid. */
    private int readRemoteBatteryPercent() {
        long now = SystemClock.elapsedRealtime();
        if (cachedRemoteBattery >= 0 && now - batteryReadAt < 30000) return cachedRemoteBattery;

        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            boolean present = battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
            if (present && level > 0 && scale > 0) {
                return cacheBattery(Math.round(level * 100f / scale), now);
            }
        }
        int property = ((BatteryManager) getSystemService(BATTERY_SERVICE))
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (property > 0) return cacheBattery(property, now);

        // SIYI's Android 9 build marks the battery as absent, while this kernel node is valid.
        try (BufferedReader reader = new BufferedReader(new FileReader(
                "/sys/class/power_supply/battery/capacity"))) {
            int sysfs = Integer.parseInt(reader.readLine().trim());
            if (sysfs >= 0 && sysfs <= 100) return cacheBattery(sysfs, now);
        } catch (IOException | NumberFormatException ignored) {
            // Keep the last valid value instead of displaying a fabricated percentage.
        }
        batteryReadAt = now;
        return cachedRemoteBattery;
    }

    private int cacheBattery(int value, long readAt) {
        cachedRemoteBattery = Math.max(0, Math.min(100, value));
        batteryReadAt = readAt;
        return cachedRemoteBattery;
    }

    @Override public void onError(String message, Throwable error) {
        runOnUiThread(() -> {
            dashboard.setCommunicationError(message);
            dashboard.showMessage("通信错误：" + message);
        });
    }

    @Override public void onAction(String action) {
        switch (action) {
            case "scan_a":
                dashboard.showMessage("已切换到 A 扫波形");
                break;
            case "scan_b":
                dashboard.showMessage("已切换到 B 扫波形");
                break;
            case "video_play":
                dashboard.showMessage("播放入口正常；配置实际 RTSP 地址后才能播放视频");
                break;
            case "video_primary":
                videoPrimary = true;
                updateVideoLayout();
                refreshSystemUi();
                break;
            case "data_primary":
                videoPrimary = false;
                updateVideoLayout();
                refreshSystemUi();
                break;
            case "zero":
                sendMeasurementCommand(TelemetrySource.MeasurementCommand.ZERO);
                break;
            case "emergency":
                dashboard.showMessage("车辆解锁/急停仅由 SA 拨杆经 SBUS 控制，不经 UART");
                break;
            case "start":
                sendMeasurementCommand(TelemetrySource.MeasurementCommand.START);
                break;
            case "stop":
                sendMeasurementCommand(TelemetrySource.MeasurementCommand.STOP);
                break;
            case "mark":
                markCount++;
                if (latestSnapshot != null) appendSample(latestSnapshot, "MARK_" + markCount);
                dashboard.showMessage("已添加标记点 " + markCount);
                break;
            case "save":
                saveCsv();
                break;
            case "export":
                exportReport();
                break;
            case "replay":
            case "history_open":
                showSavedMeasurements();
                break;
            default:
                dashboard.showMessage("未知操作：" + action);
        }
    }

    private void updateVideoLayout() {
        if (rootView == null || videoSurface == null
                || rootView.getWidth() <= 0 || rootView.getHeight() <= 0) return;
        float scale = Math.min(rootView.getWidth() / DESIGN_WIDTH,
                rootView.getHeight() / DESIGN_HEIGHT);
        float offsetX = (rootView.getWidth() - DESIGN_WIDTH * scale) / 2f;
        float offsetY = (rootView.getHeight() - DESIGN_HEIGHT * scale) / 2f;
        float left = videoPrimary ? 0f : 1190f;
        float top = videoPrimary ? 82f : 650f;
        float right = videoPrimary ? 1600f : 1572f;
        float bottom = videoPrimary ? 1000f : 968f;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoSurface.getLayoutParams();
        params.width = Math.max(1, Math.round((right - left) * scale));
        params.height = Math.max(1, Math.round((bottom - top) * scale));
        params.leftMargin = Math.round(offsetX + left * scale);
        params.topMargin = Math.round(offsetY + top * scale);
        videoSurface.setLayoutParams(params);
    }

    private void sendMeasurementCommand(TelemetrySource.MeasurementCommand command) {
        if (!telemetrySource.supportsCommands()) {
            dashboard.showMessage("当前链路不支持测厚控制");
            return;
        }
        dashboard.showMessage("正在通过 UART 发送“" + command.displayName + "”，等待 NX 确认…");
        telemetrySource.sendCommand(command, (completed, accepted, detail) -> runOnUiThread(() -> {
            dashboard.showMessage((accepted ? "成功：" : "失败：") + completed.displayName + "；" + detail);
        }));
    }

    private void appendSample(TelemetrySnapshot value, String event) {
        if (recordedRows.size() >= 18000) recordedRows.removeFirst();
        long sampleTime = "DATA".equals(event) ? value.thicknessDataMs : value.timestampMs;
        recordedRows.addLast(String.format(Locale.US,
                "%d,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d",
                sampleTime, event, value.scanSide, value.thickness, value.maximum,
                value.minimum, value.average, value.speed, value.vehicleBattery, value.faultCode,
                Math.max(0, sampleTime - recordingRoundStartMs), recordingRoundStartMs));
    }

    private File outputDirectory() throws IOException {
        File root = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (root == null) root = getFilesDir();
        File directory = new File(root, "ClimbWheel");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建目录 " + directory.getAbsolutePath());
        }
        return directory;
    }

    private void saveCsv() {
        List<String> rows = new ArrayList<>(recordedRows);
        String timestamp = fileClock.format(new Date());
        long roundEnd = latestSnapshot == null ? System.currentTimeMillis() : latestSnapshot.timestampMs;
        fileExecutor.execute(() -> {
            try {
                File destination = new File(outputDirectory(), "task_" + timestamp + ".csv");
                File pending = new File(destination.getAbsolutePath() + ".tmp");
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(pending), StandardCharsets.UTF_8)) {
                    writer.write("timestamp_ms,event,scan,thickness_um,max_um,min_um,avg_um,speed_mps,battery_percent,fault_code,elapsed_ms,round_start_ms,round_end_ms\n");
                    for (String row : rows) writer.write(row + "," + roundEnd + "\n");
                }
                if (!pending.renameTo(destination)) throw new IOException("文件保存未完成");
                runOnUiThread(() -> {
                    lastSavedFile = destination;
                    dashboard.showMessage("已保存");
                });
            } catch (IOException e) {
                runOnUiThread(() -> dashboard.showMessage("保存失败：" + e.getMessage()));
            }
        });
    }

    private void showSavedMeasurements() {
        if (historyDialog != null && historyDialog.isShowing()) return;
        fileExecutor.execute(() -> {
            try {
                File[] files = outputDirectory().listFiles(file -> file.isFile()
                        && file.getName().startsWith("task_") && file.getName().endsWith(".csv"));
                if (files == null) throw new IOException("无法读取存储数据");
                Arrays.sort(files, (left, right) -> {
                    int order = Long.compare(right.lastModified(), left.lastModified());
                    return order != 0 ? order : right.getName().compareTo(left.getName());
                });
                SimpleDateFormat displayTime = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA);
                SimpleDateFormat storedTime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
                String[] labels = new String[files.length];
                for (int i = 0; i < files.length; i++) {
                    Date date = new Date(files[i].lastModified());
                    try {
                        Date parsed = storedTime.parse(files[i].getName().substring(5, 20));
                        if (parsed != null) date = parsed;
                    } catch (java.text.ParseException | IndexOutOfBoundsException ignored) { }
                    labels[i] = displayTime.format(date) + "    "
                            + String.format(Locale.CHINA, "%.1f KB", files[i].length() / 1024f);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    AlertDialog.Builder builder = new AlertDialog.Builder(this)
                            .setTitle("历史数据")
                            .setNegativeButton("关闭", null);
                    if (files.length == 0) builder.setMessage("暂无保存记录");
                    else {
                        ListView list = new ListView(this);
                        list.setAdapter(new BaseAdapter() {
                            @Override public int getCount() { return files.length; }
                            @Override public Object getItem(int position) { return files[position]; }
                            @Override public long getItemId(int position) { return position; }
                            @Override public View getView(int position, View recycled, ViewGroup parent) {
                                LinearLayout row = new LinearLayout(MainActivity.this);
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                int pad = Math.round(14 * getResources().getDisplayMetrics().density);
                                row.setPadding(pad, pad / 2, pad, pad / 2);
                                TextView label = new TextView(MainActivity.this);
                                label.setText(labels[position]);
                                label.setTextSize(18);
                                label.setTextColor(Color.rgb(25, 42, 55));
                                label.setPadding(0, pad, pad, pad);
                                label.setContentDescription("查看记录 " + files[position].getName());
                                row.addView(label, new LinearLayout.LayoutParams(0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                                Button delete = new Button(MainActivity.this);
                                delete.setText("删除");
                                delete.setTextColor(Color.rgb(218, 57, 70));
                                delete.setContentDescription("删除记录 " + files[position].getName());
                                row.addView(delete, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                                View.OnClickListener open = view -> {
                                    if (historyDialog != null) historyDialog.dismiss();
                                    openSavedMeasurement(files[position]);
                                };
                                row.setOnClickListener(open);
                                label.setOnClickListener(open);
                                delete.setOnClickListener(view -> {
                                    if (historyDialog != null) historyDialog.dismiss();
                                    confirmDeleteMeasurement(files[position]);
                                });
                                return row;
                            }
                        });
                        builder.setView(list);
                    }
                    AlertDialog dialog = builder.create();
                    historyDialog = dialog;
                    dialog.setOnDismissListener(ignored -> {
                        if (historyDialog == dialog) historyDialog = null;
                        refreshSystemUi();
                    });
                    dialog.show();
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
                        dialog.getWindow().setLayout((int) (dashboard.getWidth() * .72f),
                                files.length > 0 ? (int) (dashboard.getHeight() * .75f)
                                        : WindowManager.LayoutParams.WRAP_CONTENT);
                    }
                });
            } catch (IOException error) {
                runOnUiThread(() -> dashboard.showMessage("读取失败：" + error.getMessage()));
            }
        });
    }

    private void confirmDeleteMeasurement(File file) {
        if (isFinishing() || isDestroyed()) return;
        boolean[] deleting = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除历史数据")
                .setMessage("确定删除此记录？\n" + file.getName() + "\n删除后无法在应用内恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (ignored, which) -> {
                    deleting[0] = true;
                    fileExecutor.execute(() -> {
                        String message;
                        try {
                            File selected = file.getCanonicalFile();
                            File directory = outputDirectory().getCanonicalFile();
                            if (!directory.equals(selected.getParentFile())
                                    || !selected.getName().startsWith("task_")
                                    || !selected.getName().endsWith(".csv")) {
                                throw new IOException("无效的记录路径");
                            }
                            if (!selected.isFile() || !selected.delete()) {
                                throw new IOException("记录不存在或无法删除");
                            }
                            message = "已删除记录";
                        } catch (IOException error) {
                            message = "删除失败：" + error.getMessage();
                        }
                        String result = message;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (file.equals(lastSavedFile)) lastSavedFile = null;
                            dashboard.showMessage(result);
                            showSavedMeasurements();
                        });
                    });
                }).create();
        historyDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (historyDialog == dialog) historyDialog = null;
            refreshSystemUi();
            if (!deleting[0] && !isFinishing() && !isDestroyed()) {
                dashboard.post(this::showSavedMeasurements);
            }
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        }
    }

    private void openSavedMeasurement(File file) {
        fileExecutor.execute(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                SavedMeasurement measurement = SavedMeasurement.read(reader);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
                    DashboardView viewer = new DashboardView(this);
                    viewer.setSavedMeasurement(measurement, latestSnapshot);
                    viewer.setRemoteBattery(readRemoteBatteryPercent());
                    viewer.setActionListener(action -> {
                        if ("history_close".equals(action)) dialog.dismiss();
                    });
                    dialog.setContentView(viewer);
                    dialog.setOnDismissListener(ignored -> {
                        historyDashboard = null;
                        if (historyDialog == dialog) historyDialog = null;
                        refreshSystemUi();
                    });
                    historyDialog = dialog;
                    historyDashboard = viewer;
                    dialog.show();
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        dialog.getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
                        dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT);
                    }
                });
            } catch (IOException error) {
                runOnUiThread(() -> dashboard.showMessage("打开失败：" + error.getMessage()));
            }
        });
    }

    private void exportReport() {
        int rowCount = recordedRows.size();
        int marks = markCount;
        TelemetrySnapshot snapshot = latestSnapshot;
        String timestamp = fileClock.format(new Date());
        dashboard.showMessage("正在后台导出报告…");
        fileExecutor.execute(() -> {
            try {
                File destination = new File(outputDirectory(), "report_" + timestamp + ".txt");
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(destination), StandardCharsets.UTF_8)) {
                    writer.write("涂层测厚机器人任务报告\n");
                    writer.write("导出时间：" + new Date() + "\n");
                    writer.write("记录条数：" + rowCount + "\n");
                    writer.write("标记点数：" + marks + "\n");
                    if (snapshot != null) {
                        writer.write(String.format(Locale.CHINA,
                                "当前厚度：%.1f μm\n最大/最小/平均：%.1f / %.1f / %.1f μm\n电量：%d%%\n故障码：%d\n",
                                snapshot.thickness, snapshot.maximum, snapshot.minimum,
                                snapshot.average, snapshot.vehicleBattery, snapshot.faultCode));
                    }
                writer.write("说明：UART 用于测厚遥测和测厚装置控制；车辆解锁/急停仅由 SA/SBUS 控制。\n");
                }
                runOnUiThread(() -> dashboard.showMessage("报告已导出：" + destination.getName()));
            } catch (IOException e) {
                runOnUiThread(() -> dashboard.showMessage("导出失败：" + e.getMessage()));
            }
        });
    }
}
