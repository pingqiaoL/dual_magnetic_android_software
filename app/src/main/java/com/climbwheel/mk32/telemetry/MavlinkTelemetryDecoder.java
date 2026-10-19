package com.climbwheel.mk32.telemetry;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Maps CRC-validated MAVLink common messages into the dashboard state. */
public final class MavlinkTelemetryDecoder {
    public interface ProtocolListener {
        void onCommandAck(int command, int result);
        void onPingRequest(long timeUsec, long sequence, int sourceSystem, int sourceComponent);
        void onPingResponse(long timeUsec, long sequence);
    }

    private final MavlinkFrameParser parser = new MavlinkFrameParser();
    private final ProtocolListener protocolListener;
    private TelemetrySnapshot state = TelemetrySnapshot.builder().build();

    public MavlinkTelemetryDecoder() { this(null); }
    public MavlinkTelemetryDecoder(ProtocolListener protocolListener) {
        this.protocolListener = protocolListener;
    }

    public TelemetrySnapshot accept(int value) {
        MavlinkMessage message = parser.accept(value);
        if (message == null) return null;
        TelemetrySnapshot.Builder b = state.buildUpon()
                .timestampMs(System.currentTimeMillis())
                .linkState(TelemetrySnapshot.LinkState.CONNECTED);
        long now = System.currentTimeMillis();
        boolean changed;
        switch (message.messageId) {
            case 0: changed = heartbeat(message, b, now); break;
            case 1: changed = systemStatus(message.payload, b); break;
            case 4: changed = ping(message, b, now); break;
            case 32: changed = localPosition(message.payload, b); break;
            case 74: changed = vfrHud(message.payload, b); break;
            case 77: changed = commandAck(message.payload, b, now); break;
            case 147: changed = batteryStatus(message.payload, b); break;
            case 251: changed = namedFloat(message.payload, b, now); break;
            case 252: changed = namedInt(message.payload, b, now); break;
            case 350: changed = debugFloatArray(message.payload, b, now); break;
            default: changed = false;
        }
        if (!changed) return null;
        state = b.build();
        return state;
    }

    private static boolean heartbeat(MavlinkMessage message, TelemetrySnapshot.Builder b, long now) {
        byte[] p = message.payload;
        if (p.length < 9) return false;
        if (message.systemId != MavlinkEncoder.NX_SYSTEM_ID
                || message.componentId != MavlinkEncoder.NX_COMPONENT_ID) return false;
        int baseMode = u8(p, 6);
        int systemStatus = u8(p, 7);
        b.nxHeartbeatMs(now);
        if (systemStatus >= 5) {
            b.vehicleState(TelemetrySnapshot.VehicleState.EXCEPTION).faultCode(systemStatus);
        } else {
            b.vehicleState((baseMode & 0x80) != 0
                    ? TelemetrySnapshot.VehicleState.UNLOCKED
                    : TelemetrySnapshot.VehicleState.LOCKED);
        }
        return true;
    }

    private boolean ping(MavlinkMessage message, TelemetrySnapshot.Builder b, long now) {
        byte[] p = message.payload;
        if (p.length < 14) return false;
        long timeUsec = i64(p, 0);
        long sequence = u32(p, 8);
        int targetSystem = u8(p, 12);
        int targetComponent = u8(p, 13);
        if (targetSystem == 0 && targetComponent == 0) {
            if (protocolListener != null) protocolListener.onPingRequest(
                    timeUsec, sequence, message.systemId, message.componentId);
            return false;
        }
        if (targetSystem == MavlinkEncoder.GCS_SYSTEM_ID
                && targetComponent == MavlinkEncoder.GCS_COMPONENT_ID) {
            b.duplexConfirmedMs(now);
            if (protocolListener != null) protocolListener.onPingResponse(timeUsec, sequence);
            return true;
        }
        return false;
    }

    private boolean commandAck(byte[] p, TelemetrySnapshot.Builder b, long now) {
        if (p.length < 3) return false;
        int command = u16(p, 0);
        int result = u8(p, 2);
        b.duplexConfirmedMs(now);
        if (protocolListener != null) protocolListener.onCommandAck(command, result);
        return true;
    }

    private static boolean systemStatus(byte[] p, TelemetrySnapshot.Builder b) {
        if (p.length < 31) return false;
        int battery = p[30];
        if (battery >= 0 && battery <= 100) b.vehicleBattery(battery);
        int errorsCommunication = u16(p, 20);
        int errorsCount1 = u16(p, 22);
        if (errorsCount1 != 0) b.faultCode(errorsCount1);
        else if (errorsCommunication != 0) b.faultCode(errorsCommunication);
        return true;
    }

    private static boolean localPosition(byte[] p, TelemetrySnapshot.Builder b) {
        if (p.length < 28) return false;
        float vx = f32(p, 16), vy = f32(p, 20), vz = f32(p, 24);
        b.speed((float) Math.sqrt(vx * vx + vy * vy + vz * vz));
        return true;
    }

    private static boolean vfrHud(byte[] p, TelemetrySnapshot.Builder b) {
        if (p.length < 20) return false;
        b.speed(f32(p, 4));
        return true;
    }

    private static boolean batteryStatus(byte[] p, TelemetrySnapshot.Builder b) {
        if (p.length < 36) return false;
        int battery = p[35];
        if (battery >= 0 && battery <= 100) b.vehicleBattery(battery);
        return true;
    }

    private static boolean namedFloat(byte[] p, TelemetrySnapshot.Builder b, long now) {
        if (p.length < 18) return false;
        return applyNamed(name(p, 8, 10), f32(p, 4), b, now);
    }

    private static boolean namedInt(byte[] p, TelemetrySnapshot.Builder b, long now) {
        if (p.length < 18) return false;
        return applyNamed(name(p, 8, 10), i32(p, 4), b, now);
    }

    private static boolean applyNamed(String rawName, float value, TelemetrySnapshot.Builder b, long now) {
        String name = rawName.toUpperCase(Locale.US);
        switch (name) {
            case "THICKNESS": b.thickness(value); break;
            case "THICK_MAX": b.maximum(value); break;
            case "THICK_MIN": b.minimum(value); break;
            case "THICK_AVG": b.average(value); break;
            case "SPEED": b.speed(value); break;
            case "VEH_BATT": b.vehicleBattery(Math.round(value)); break;
            case "RC_BATT": b.remoteBattery(Math.round(value)); return true;
            case "FAULT": b.faultCode(Math.round(value)); break;
            case "MEAS_RUN":
                b.measureState(value > 0.5f ? TelemetrySnapshot.MeasureState.RUNNING
                        : TelemetrySnapshot.MeasureState.STOPPED); break;
            case "SCAN_SIDE": b.scanSide(value > 0.5f ? "B" : "A"); break;
            case "STM_LINK":
                b.stm32Connected(value > 0.5f);
                if (value > 0.5f) b.stm32DataMs(now);
                return true;
            case "VEH_STATE":
                int state = Math.round(value);
                b.vehicleState(state == 2 ? TelemetrySnapshot.VehicleState.UNLOCKED
                        : state == 1 ? TelemetrySnapshot.VehicleState.LOCKED
                        : TelemetrySnapshot.VehicleState.EXCEPTION);
                return true;
            default: return false;
        }
        b.stm32Connected(true).stm32DataMs(now);
        return true;
    }

    private static boolean debugFloatArray(byte[] p, TelemetrySnapshot.Builder b, long now) {
        if (p.length < 24) return false;
        String name = name(p, 10, 10).toUpperCase(Locale.US);
        if (!"WAVE_A".equals(name) && !"WAVE_B".equals(name)) return false;
        int count = Math.min(58, (p.length - 20) / 4);
        if (count <= 0) return false;
        float[] wave = new float[count];
        for (int i = 0; i < count; i++) wave[i] = f32(p, 20 + i * 4);
        b.scanSide("WAVE_B".equals(name) ? "B" : "A").waveform(wave)
                .stm32Connected(true).stm32DataMs(now);
        return true;
    }

    private static int u8(byte[] p, int at) { return p[at] & 0xff; }
    private static int u16(byte[] p, int at) { return buffer(p, at, 2).getShort() & 0xffff; }
    private static int i32(byte[] p, int at) { return buffer(p, at, 4).getInt(); }
    private static long u32(byte[] p, int at) { return i32(p, at) & 0xffffffffL; }
    private static long i64(byte[] p, int at) { return buffer(p, at, 8).getLong(); }
    private static float f32(byte[] p, int at) { return buffer(p, at, 4).getFloat(); }
    private static ByteBuffer buffer(byte[] p, int at, int length) {
        return ByteBuffer.wrap(p, at, length).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static String name(byte[] p, int at, int length) {
        int end = at;
        while (end < at + length && end < p.length && p[end] != 0) end++;
        return new String(p, at, end - at, StandardCharsets.US_ASCII).trim();
    }
}
