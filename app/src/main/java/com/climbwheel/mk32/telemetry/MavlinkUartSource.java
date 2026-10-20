package com.climbwheel.mk32.telemetry;

import android.util.Log;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bidirectional MAVLink transport between the MK32 Android app and ORIN-NX. */
public final class MavlinkUartSource implements TelemetrySource, MavlinkTelemetryDecoder.ProtocolListener {
    private static final String TAG = "Mk32Mavlink";
    private static final String STTY = "/system/bin/stty";
    public static final String DEFAULT_DEVICE = "/dev/ttyHS0";
    private static final long RX_TIMEOUT_MS = 3500;
    private static final long COMMAND_RETRY_MS = 900;
    private static final int COMMAND_MAX_ATTEMPTS = 3;

    private final File device;
    private final Object outputLock = new Object();
    private final Object commandLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger pingSequence = new AtomicInteger();
    private final AtomicLong receivedBytes = new AtomicLong();
    private final AtomicLong transmittedBytes = new AtomicLong();
    private final AtomicLong parsedSnapshots = new AtomicLong();
    private final MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(this);
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile long lastRxAt;
    private volatile Listener listener;
    private FileInputStream input;
    private FileOutputStream output;
    private PendingCommand pending;
    private boolean writeErrorReported;

    public MavlinkUartSource(String path) { device = new File(path); }

    public boolean exists() { return device.exists(); }
    public boolean isAccessible() { return device.exists() && device.canRead() && device.canWrite(); }

    @Override public synchronized void start(Listener listener) {
        stop();
        this.listener = listener;
        try {
            String serialSettings = configureSerialPort(device.getAbsolutePath());
            input = new FileInputStream(device);
            output = new FileOutputStream(device);
            Log.i(TAG, "UART ready: " + oneLine(serialSettings));
        } catch (IOException e) {
            closePorts();
            Log.e(TAG, "UART initialization failed for " + device.getAbsolutePath(), e);
            listener.onError("无法配置或双向打开 " + device.getAbsolutePath()
                    + "（需要 115200 8N1 raw 和读写权限）", e);
            return;
        }
        receivedBytes.set(0);
        transmittedBytes.set(0);
        parsedSnapshots.set(0);
        running = true;
        writeErrorReported = false;
        executor = Executors.newScheduledThreadPool(2);
        executor.execute(this::readLoop);
        executor.scheduleAtFixedRate(() -> write(MavlinkEncoder.heartbeat(nextSequence())),
                0, 1, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(this::sendPing, 300, 2000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::checkPendingCommand, 200, 200, TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        try {
            byte[] buffer = new byte[1024];
            int count;
            while (running && (count = input.read(buffer)) >= 0) {
                long total = receivedBytes.addAndGet(count);
                Log.d(TAG, "RX " + count + " B (total " + total + "): "
                        + hex(buffer, count, 96));
                for (int i = 0; i < count; i++) {
                    TelemetrySnapshot snapshot = decoder.accept(buffer[i] & 0xff);
                    if (snapshot != null) {
                        long parsed = parsedSnapshots.incrementAndGet();
                        Log.d(TAG, "PARSED #" + parsed
                                + " link=" + snapshot.linkState
                                + " measure=" + snapshot.measureState
                                + " vehicle=" + snapshot.vehicleState
                                + " stm32=" + snapshot.stm32Connected
                                + " thickness=" + snapshot.thickness
                                + " thicknessAt=" + snapshot.thicknessDataMs);
                        lastRxAt = System.currentTimeMillis();
                        Listener current = listener;
                        if (current != null) current.onTelemetry(snapshot);
                    }
                }
            }
            if (running) reportError("串口输入已结束", null);
        } catch (Exception e) {
            if (running) reportError("无法读取 " + device.getAbsolutePath(), e);
        } finally {
            boolean linkLost = running;
            running = false;
            closePorts();
            if (linkLost) {
                PendingCommand failed = null;
                synchronized (commandLock) {
                    if (pending != null) {
                        failed = pending;
                        pending = null;
                    }
                }
                if (failed != null) failed.callback.onResult(failed.command, false, "UART 链路已断开");
                ScheduledExecutorService current = executor;
                if (current != null) current.shutdownNow();
            }
        }
    }

    private void sendPing() {
        if (!running) return;
        int ping = pingSequence.getAndIncrement();
        write(MavlinkEncoder.ping(nextSequence(), ping, System.nanoTime() / 1000L));
    }

    @Override public boolean supportsCommands() { return true; }

    @Override public void sendCommand(MeasurementCommand command, CommandCallback callback) {
        ScheduledExecutorService current = executor;
        if (!running || current == null) {
            callback.onResult(command, false, "UART 尚未启动");
            return;
        }
        current.execute(() -> {
            if (System.currentTimeMillis() - lastRxAt > RX_TIMEOUT_MS) {
                callback.onResult(command, false, "NX/遥控链路未连接，命令未发送");
                return;
            }
            synchronized (commandLock) {
                if (pending != null) {
                    callback.onResult(command, false, "上一条控制命令仍在等待 NX 确认");
                    return;
                }
                pending = new PendingCommand(command, callback);
                transmitPendingLocked();
            }
        });
    }

    private void checkPendingCommand() {
        PendingCommand timedOut = null;
        synchronized (commandLock) {
            if (pending == null || System.currentTimeMillis() < pending.nextAttemptAt) return;
            if (pending.attempts >= COMMAND_MAX_ATTEMPTS) {
                timedOut = pending;
                pending = null;
            } else {
                transmitPendingLocked();
            }
        }
        if (timedOut != null) timedOut.callback.onResult(timedOut.command, false,
                "NX 未返回 COMMAND_ACK，已重试 " + COMMAND_MAX_ATTEMPTS + " 次");
    }

    private void transmitPendingLocked() {
        if (pending == null) return;
        int confirmation = pending.attempts;
        write(MavlinkEncoder.commandLong(nextSequence(), pending.command.mavCommand, confirmation));
        pending.attempts++;
        pending.nextAttemptAt = System.currentTimeMillis() + COMMAND_RETRY_MS;
    }

    @Override public void onCommandAck(int command, int result) {
        PendingCommand completed;
        synchronized (commandLock) {
            if (pending == null || pending.command.mavCommand != command) return;
            if (result == 5) {
                pending.nextAttemptAt = System.currentTimeMillis() + 5000;
                return;
            }
            completed = pending;
            pending = null;
        }
        completed.callback.onResult(completed.command, result == 0, ackText(result));
    }

    @Override public void onPingRequest(long timeUsec, long ping, int sourceSystem, int sourceComponent) {
        write(MavlinkEncoder.ping(nextSequence(), (int) ping, timeUsec, sourceSystem, sourceComponent));
    }

    @Override public void onPingResponse(long timeUsec, long ping) {
        lastRxAt = System.currentTimeMillis();
    }

    private boolean write(byte[] bytes) {
        if (!running) return false;
        synchronized (outputLock) {
            try {
                if (output == null) return false;
                output.write(bytes);
                output.flush();
                long total = transmittedBytes.addAndGet(bytes.length);
                Log.d(TAG, "TX " + bytes.length + " B (total " + total + "): "
                        + hex(bytes, bytes.length, 96));
                return true;
            } catch (IOException e) {
                if (!writeErrorReported) {
                    writeErrorReported = true;
                    reportError("无法写入 " + device.getAbsolutePath(), e);
                }
                return false;
            }
        }
    }

    private void reportError(String message, Throwable error) {
        Listener current = listener;
        if (current != null) current.onError(message, error);
    }

    @Override public synchronized void stop() {
        running = false;
        synchronized (commandLock) {
            if (pending != null) {
                pending.callback.onResult(pending.command, false, "UART 已停止");
                pending = null;
            }
        }
        closePorts();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        listener = null;
    }

    private synchronized void closePorts() {
        if (input != null) {
            try { input.close(); } catch (Exception ignored) { }
            input = null;
        }
        if (output != null) {
            try { output.close(); } catch (Exception ignored) { }
            output = null;
        }
    }

    private int nextSequence() { return sequence.getAndIncrement() & 0xff; }

    /** Configure the MK32 UART before Java streams open it; opening a tty does not set termios. */
    private static String configureSerialPort(String path) throws IOException {
        runStty("-F", path, "raw", "115200");
        // MK32's Android 9 toybox leaves some local echo flags unchanged after "raw".
        runStty("-F", path, "-echo", "-echoe", "-echok", "-echonl",
                "-echoprt", "-echoctl", "-echoke", "-iexten",
                "-icrnl", "-inlcr", "-igncr", "-ixon", "-ixoff",
                "-inpck", "-istrip", "-iuclc", "-ixany", "-imaxbel", "-iutf8");
        String settings = runStty("-F", path, "-a");
        String normalized = " " + oneLine(settings) + " ";
        requireSetting(normalized.contains("speed 115200 baud"), "115200 baud", settings);
        requireSetting(normalized.contains(" cs8 "), "8 data bits", settings);
        requireSetting(normalized.contains(" -parenb "), "no parity", settings);
        requireSetting(normalized.contains(" -cstopb "), "1 stop bit", settings);
        requireSetting(normalized.contains(" -icanon "), "non-canonical/raw input", settings);
        requireSetting(normalized.contains(" -echo "), "echo disabled", settings);
        requireSetting(normalized.contains(" -istrip "), "8-bit input preserved", settings);
        requireSetting(normalized.contains(" -icrnl "), "CR bytes preserved", settings);
        requireSetting(normalized.contains(" -ixon "), "software flow control disabled", settings);
        return settings;
    }

    private static String runStty(String... arguments) throws IOException {
        String[] command = new String[arguments.length + 1];
        command[0] = STTY;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
        }
        try {
            int result = process.waitFor();
            if (result != 0) {
                throw new IOException("stty exit=" + result + ": " + oneLine(output.toString()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 stty 配置串口时被中断", e);
        }
        return output.toString();
    }

    private static void requireSetting(boolean condition, String expected, String actual)
            throws IOException {
        if (!condition) {
            throw new IOException("串口未进入 " + expected + "；实际：" + oneLine(actual));
        }
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String hex(byte[] bytes, int count, int limit) {
        int shown = Math.min(count, limit);
        StringBuilder out = new StringBuilder(shown * 3 + 20);
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(' ');
            int value = bytes[i] & 0xff;
            if (value < 0x10) out.append('0');
            out.append(Integer.toHexString(value));
        }
        if (shown < count) out.append(" ... +").append(count - shown).append(" B");
        return out.toString();
    }

    @Override public String description() { return "MK32 ↔ ORIN-NX MAVLink " + device.getAbsolutePath(); }

    private static String ackText(int result) {
        switch (result) {
            case 0: return "NX 已接受并确认";
            case 1: return "NX 暂时拒绝（设备忙）";
            case 2: return "NX 拒绝：命令不支持";
            case 3: return "NX 拒绝：命令失败";
            case 4: return "NX 拒绝：参数无效";
            case 6: return "NX 已取消命令";
            default: return "NX 返回结果码 " + result;
        }
    }

    private static final class PendingCommand {
        final MeasurementCommand command;
        final CommandCallback callback;
        int attempts;
        long nextAttemptAt;

        PendingCommand(MeasurementCommand command, CommandCallback callback) {
            this.command = command;
            this.callback = callback;
        }
    }
}
