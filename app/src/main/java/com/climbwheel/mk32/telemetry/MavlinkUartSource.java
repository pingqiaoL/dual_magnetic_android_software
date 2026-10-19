package com.climbwheel.mk32.telemetry;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bidirectional MAVLink transport between the MK32 Android app and ORIN-NX. */
public final class MavlinkUartSource implements TelemetrySource, MavlinkTelemetryDecoder.ProtocolListener {
    public static final String DEFAULT_DEVICE = "/dev/ttyHS0";
    private static final long RX_TIMEOUT_MS = 3500;
    private static final long COMMAND_RETRY_MS = 900;
    private static final int COMMAND_MAX_ATTEMPTS = 3;

    private final File device;
    private final Object outputLock = new Object();
    private final Object commandLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger pingSequence = new AtomicInteger();
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
            input = new FileInputStream(device);
            output = new FileOutputStream(device);
        } catch (IOException e) {
            closePorts();
            listener.onError("无法双向打开 " + device.getAbsolutePath() + "（需要读写权限）", e);
            return;
        }
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
                for (int i = 0; i < count; i++) {
                    TelemetrySnapshot snapshot = decoder.accept(buffer[i] & 0xff);
                    if (snapshot != null) {
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
