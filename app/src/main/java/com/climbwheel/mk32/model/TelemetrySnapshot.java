package com.climbwheel.mk32.model;

import java.util.Arrays;

/** Immutable state consumed by the dashboard. */
public final class TelemetrySnapshot {
    public enum LinkState { DEMO, CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    public enum MeasureState { STOPPED, RUNNING }
    public enum VehicleState { EXCEPTION, LOCKED, UNLOCKED }

    public final long timestampMs;
    public final LinkState linkState;
    public final MeasureState measureState;
    public final float thickness;
    public final float maximum;
    public final float minimum;
    public final float average;
    public final float speed;
    public final int vehicleBattery;
    public final int remoteBattery;
    public final int faultCode;
    public final String scanSide;
    public final float[] waveform;
    public final VehicleState vehicleState;
    public final boolean stm32Connected;
    public final long nxHeartbeatMs;
    public final long stm32DataMs;
    public final long duplexConfirmedMs;
    /** Local wall-clock time when a new THICKNESS value was decoded. */
    public final long thicknessDataMs;

    private TelemetrySnapshot(Builder b) {
        timestampMs = b.timestampMs;
        linkState = b.linkState;
        measureState = b.measureState;
        thickness = b.thickness;
        maximum = b.maximum;
        minimum = b.minimum;
        average = b.average;
        speed = b.speed;
        vehicleBattery = b.vehicleBattery;
        remoteBattery = b.remoteBattery;
        faultCode = b.faultCode;
        scanSide = b.scanSide;
        waveform = Arrays.copyOf(b.waveform, b.waveform.length);
        vehicleState = b.vehicleState;
        stm32Connected = b.stm32Connected;
        nxHeartbeatMs = b.nxHeartbeatMs;
        stm32DataMs = b.stm32DataMs;
        duplexConfirmedMs = b.duplexConfirmedMs;
        thicknessDataMs = b.thicknessDataMs;
    }

    public static Builder builder() { return new Builder(); }

    public Builder buildUpon() {
        return new Builder()
                .timestampMs(timestampMs).linkState(linkState).measureState(measureState)
                .thickness(thickness).maximum(maximum).minimum(minimum).average(average)
                .speed(speed).vehicleBattery(vehicleBattery).remoteBattery(remoteBattery)
                .faultCode(faultCode).scanSide(scanSide).waveform(waveform)
                .vehicleState(vehicleState).stm32Connected(stm32Connected)
                .nxHeartbeatMs(nxHeartbeatMs).stm32DataMs(stm32DataMs)
                .duplexConfirmedMs(duplexConfirmedMs).thicknessDataMs(thicknessDataMs);
    }

    public static final class Builder {
        private long timestampMs = System.currentTimeMillis();
        private LinkState linkState = LinkState.DISCONNECTED;
        private MeasureState measureState = MeasureState.STOPPED;
        private float thickness;
        private float maximum;
        private float minimum;
        private float average;
        private float speed;
        private int vehicleBattery;
        private int remoteBattery;
        private int faultCode;
        private String scanSide = "A";
        private float[] waveform = new float[0];
        private VehicleState vehicleState = VehicleState.EXCEPTION;
        private boolean stm32Connected;
        private long nxHeartbeatMs;
        private long stm32DataMs;
        private long duplexConfirmedMs;
        private long thicknessDataMs;

        public Builder timestampMs(long v) { timestampMs = v; return this; }
        public Builder linkState(LinkState v) { linkState = v; return this; }
        public Builder measureState(MeasureState v) { measureState = v; return this; }
        public Builder thickness(float v) { thickness = v; return this; }
        public Builder maximum(float v) { maximum = v; return this; }
        public Builder minimum(float v) { minimum = v; return this; }
        public Builder average(float v) { average = v; return this; }
        public Builder speed(float v) { speed = v; return this; }
        public Builder vehicleBattery(int v) { vehicleBattery = clamp(v, 0, 100); return this; }
        public Builder remoteBattery(int v) { remoteBattery = clamp(v, 0, 100); return this; }
        public Builder faultCode(int v) { faultCode = Math.max(0, v); return this; }
        public Builder scanSide(String v) { scanSide = v == null ? "A" : v; return this; }
        public Builder waveform(float[] v) { waveform = v == null ? new float[0] : Arrays.copyOf(v, v.length); return this; }
        public Builder vehicleState(VehicleState v) { vehicleState = v == null ? VehicleState.EXCEPTION : v; return this; }
        public Builder stm32Connected(boolean v) { stm32Connected = v; return this; }
        public Builder nxHeartbeatMs(long v) { nxHeartbeatMs = v; return this; }
        public Builder stm32DataMs(long v) { stm32DataMs = v; return this; }
        public Builder duplexConfirmedMs(long v) { duplexConfirmedMs = v; return this; }
        public Builder thicknessDataMs(long v) { thicknessDataMs = v; return this; }
        public TelemetrySnapshot build() { return new TelemetrySnapshot(this); }
        private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    }
}
