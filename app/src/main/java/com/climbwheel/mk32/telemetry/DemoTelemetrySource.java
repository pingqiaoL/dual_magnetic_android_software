package com.climbwheel.mk32.telemetry;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Generates realistic values so the UI can be developed inside a VM without MK32 hardware. */
public final class DemoTelemetrySource implements TelemetrySource {
    private final Random random = new Random(32);
    private final Deque<Float> samples = new ArrayDeque<>();
    private ScheduledExecutorService executor;
    private int tick;
    private volatile boolean measurementRunning;

    @Override public synchronized void start(Listener listener) {
        stop();
        for (int i = 0; i < 80; i++) samples.add(128.3f);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            tick++;
            float settling = tick < 18 ? (float) (11.0 * Math.sin(tick * 1.7) * Math.exp(-tick / 5.5)) : 0f;
            float value = 128.3f + settling + (random.nextFloat() - 0.5f) * 0.5f;
            synchronized (samples) {
                samples.removeFirst();
                samples.addLast(value);
                float[] wave = new float[samples.size()];
                int i = 0;
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, sum = 0f;
                for (float sample : samples) {
                    wave[i++] = sample;
                    min = Math.min(min, sample); max = Math.max(max, sample); sum += sample;
                }
                listener.onTelemetry(TelemetrySnapshot.builder()
                        .linkState(TelemetrySnapshot.LinkState.DEMO)
                        .measureState(measurementRunning ? TelemetrySnapshot.MeasureState.RUNNING
                                : TelemetrySnapshot.MeasureState.STOPPED)
                        .thickness(value).maximum(max).minimum(min).average(sum / wave.length)
                        .speed(0.18f).vehicleBattery(78).remoteBattery(78).faultCode(0)
                        .scanSide((tick / 20) % 2 == 0 ? "A" : "B").waveform(wave)
                        .vehicleState(TelemetrySnapshot.VehicleState.LOCKED)
                        .stm32Connected(true).nxHeartbeatMs(System.currentTimeMillis())
                        .stm32DataMs(System.currentTimeMillis()).duplexConfirmedMs(System.currentTimeMillis())
                        .build());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override public String description() { return "虚拟机演示数据"; }

    @Override public boolean supportsCommands() { return true; }

    @Override public void sendCommand(MeasurementCommand command, CommandCallback callback) {
        if (command == MeasurementCommand.START) measurementRunning = true;
        else if (command == MeasurementCommand.STOP) measurementRunning = false;
        callback.onResult(command, true, "演示模式 ACK");
    }
}
