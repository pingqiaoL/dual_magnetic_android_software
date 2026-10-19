package com.climbwheel.mk32;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.climbwheel.mk32.model.TelemetrySnapshot;
import com.climbwheel.mk32.telemetry.DemoTelemetrySource;
import com.climbwheel.mk32.telemetry.MavlinkUartSource;
import com.climbwheel.mk32.telemetry.TelemetrySource;
import com.climbwheel.mk32.ui.DashboardView;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TelemetrySource.Listener, DashboardView.ActionListener {
    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private DashboardView dashboard;
    private TelemetrySource telemetrySource;
    private final Deque<String> recordedRows = new ArrayDeque<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat fileClock = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
    private boolean localRecording;
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
        setContentView(dashboard);

        MavlinkUartSource uart = new MavlinkUartSource(MavlinkUartSource.DEFAULT_DEVICE);
        telemetrySource = uart.exists() ? uart : new DemoTelemetrySource();
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
    }

    @Override protected void onStop() {
        dashboard.removeCallbacks(batteryUpdater);
        telemetrySource.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        fileExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onTelemetry(TelemetrySnapshot snapshot) {
        int remoteBattery = readRemoteBatteryPercent();
        TelemetrySnapshot displayed = remoteBattery >= 0
                ? snapshot.buildUpon().remoteBattery(remoteBattery).build() : snapshot;
        runOnUiThread(() -> {
            latestSnapshot = displayed;
            if (localRecording) appendSample(displayed, "DATA");
            dashboard.setTelemetry(displayed);
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
                refreshSystemUi();
                break;
            case "data_primary":
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
                if (lastSavedFile == null) {
                    dashboard.showMessage("请先点击“保存任务”，再进行数据回放");
                } else {
                    dashboard.showMessage("已找到任务文件；波形回放解析将在下一阶段接入");
                }
                break;
            default:
                dashboard.showMessage("未知操作：" + action);
        }
    }

    private void sendMeasurementCommand(TelemetrySource.MeasurementCommand command) {
        if (!telemetrySource.supportsCommands()) {
            dashboard.showMessage("当前链路不支持测厚控制");
            return;
        }
        dashboard.showMessage("正在通过 UART 发送“" + command.displayName + "”，等待 NX 确认…");
        telemetrySource.sendCommand(command, (completed, accepted, detail) -> runOnUiThread(() -> {
            if (accepted && completed == TelemetrySource.MeasurementCommand.START) {
                localRecording = true;
                recordedRows.clear();
                markCount = 0;
                dashboard.setLocalRecording(true);
                if (latestSnapshot != null) appendSample(latestSnapshot, "START_ACK");
            } else if (accepted && completed == TelemetrySource.MeasurementCommand.STOP) {
                if (localRecording && latestSnapshot != null) appendSample(latestSnapshot, "STOP_ACK");
                localRecording = false;
                dashboard.setLocalRecording(false);
            }
            dashboard.showMessage((accepted ? "成功：" : "失败：") + completed.displayName + "；" + detail);
        }));
    }

    private void appendSample(TelemetrySnapshot value, String event) {
        if (recordedRows.size() >= 18000) recordedRows.removeFirst();
        recordedRows.addLast(String.format(Locale.US,
                "%d,%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d",
                value.timestampMs, event, value.scanSide, value.thickness, value.maximum,
                value.minimum, value.average, value.speed, value.vehicleBattery, value.faultCode));
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
        dashboard.showMessage("正在后台保存任务…");
        fileExecutor.execute(() -> {
            try {
                File destination = new File(outputDirectory(), "task_" + timestamp + ".csv");
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(destination), StandardCharsets.UTF_8)) {
                    writer.write("timestamp_ms,event,scan,thickness_um,max_um,min_um,avg_um,speed_mps,battery_percent,fault_code\n");
                    for (String row : rows) writer.write(row + "\n");
                }
                runOnUiThread(() -> {
                    lastSavedFile = destination;
                    dashboard.showMessage("任务已保存：" + destination.getName() + "（" + rows.size() + " 条）");
                });
            } catch (IOException e) {
                runOnUiThread(() -> dashboard.showMessage("保存失败：" + e.getMessage()));
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
