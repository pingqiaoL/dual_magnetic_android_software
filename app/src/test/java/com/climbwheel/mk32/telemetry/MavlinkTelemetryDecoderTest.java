package com.climbwheel.mk32.telemetry;

import com.climbwheel.mk32.model.TelemetrySnapshot;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MavlinkTelemetryDecoderTest {
    @Test public void parsesMavlink1HeartbeatAndNamedThickness() {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder();
        byte[] heartbeat = mav1(0, 50, new byte[9]);
        TelemetrySnapshot connected = feed(decoder, heartbeat);
        assertNotNull(connected);
        assertEquals(TelemetrySnapshot.LinkState.CONNECTED, connected.linkState);
        assertEquals(TelemetrySnapshot.VehicleState.LOCKED, connected.vehicleState);

        ByteBuffer payload = little(18);
        payload.putInt(1234).putFloat(128.6f).put(fixedName("THICKNESS"));
        TelemetrySnapshot measured = feed(decoder, mav1(251, 170, payload.array()));
        assertNotNull(measured);
        assertEquals(128.6f, measured.thickness, 0.001f);
        assertTrue(measured.thicknessDataMs > 0);
    }

    @Test public void mapsNxVehicleState() {
        ByteBuffer unlocked = little(18);
        unlocked.putInt(1).putFloat(2f).put(fixedName("VEH_STATE"));
        TelemetrySnapshot value = feed(new MavlinkTelemetryDecoder(),
                mav1(251, 170, unlocked.array()));
        assertEquals(TelemetrySnapshot.VehicleState.UNLOCKED, value.vehicleState);
    }

    @Test public void parsesMavlink2Waveform() {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder();
        ByteBuffer payload = little(20 + 4 * 4);
        payload.putLong(1000L).putShort((short) 1).put(fixedName("WAVE_B"));
        payload.putFloat(127.8f).putFloat(128.0f).putFloat(128.4f).putFloat(128.2f);
        TelemetrySnapshot value = feed(decoder, mav2(350, 232, payload.array()));
        assertNotNull(value);
        assertEquals("B", value.scanSide);
        assertEquals(4, value.waveform.length);
        assertEquals(128.4f, value.waveform[2], 0.001f);
    }

    @Test public void mapsBatterySpeedAndMeasurementState() {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder();
        byte[] status = new byte[31];
        status[30] = 78;
        TelemetrySnapshot battery = feed(decoder, mav1(1, 124, status));
        assertEquals(78, battery.vehicleBattery);

        ByteBuffer hud = little(20);
        hud.position(4); hud.putFloat(0.18f);
        TelemetrySnapshot speed = feed(decoder, mav1(74, 20, hud.array()));
        assertEquals(0.18f, speed.speed, 0.001f);

        ByteBuffer running = little(18);
        running.putInt(99).putFloat(1f).put(fixedName("MEAS_RUN"));
        TelemetrySnapshot state = feed(decoder, mav1(251, 170, running.array()));
        assertEquals(TelemetrySnapshot.MeasureState.RUNNING, state.measureState);
    }

    @Test public void rejectsBadCrcAndRecoversAtNextFrame() {
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder();
        byte[] bad = mav1(0, 50, new byte[9]);
        bad[bad.length - 1] ^= 0x20;
        assertNull(feed(decoder, bad));

        ByteBuffer payload = little(18);
        payload.putInt(1).putFloat(7f).put(fixedName("FAULT"));
        TelemetrySnapshot recovered = feed(decoder, mav1(251, 170, payload.array()));
        assertNotNull(recovered);
        assertEquals(7, recovered.faultCode);
    }

    private static TelemetrySnapshot feed(MavlinkTelemetryDecoder decoder, byte[] bytes) {
        TelemetrySnapshot last = null;
        for (byte value : bytes) {
            TelemetrySnapshot next = decoder.accept(value & 0xff);
            if (next != null) last = next;
        }
        return last;
    }

    private static byte[] mav1(int messageId, int crcExtra, byte[] payload) {
        byte[] frame = new byte[payload.length + 8];
        frame[0] = (byte) 0xFE; frame[1] = (byte) payload.length;
        frame[2] = 7; frame[3] = 1; frame[4] = (byte) 191; frame[5] = (byte) messageId;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        putCrc(frame, 1, 6 + payload.length, crcExtra);
        return frame;
    }

    private static byte[] mav2(int messageId, int crcExtra, byte[] payload) {
        byte[] frame = new byte[payload.length + 12];
        frame[0] = (byte) 0xFD; frame[1] = (byte) payload.length;
        frame[2] = 0; frame[3] = 0; frame[4] = 8; frame[5] = 1; frame[6] = (byte) 191;
        frame[7] = (byte) messageId; frame[8] = (byte) (messageId >>> 8); frame[9] = (byte) (messageId >>> 16);
        System.arraycopy(payload, 0, frame, 10, payload.length);
        putCrc(frame, 1, 10 + payload.length, crcExtra);
        return frame;
    }

    private static void putCrc(byte[] frame, int from, int checksumAt, int extra) {
        int crc = 0xffff;
        for (int i = from; i < checksumAt; i++) crc = MavlinkFrameParser.crcAccumulate(frame[i] & 0xff, crc);
        crc = MavlinkFrameParser.crcAccumulate(extra, crc);
        frame[checksumAt] = (byte) crc;
        frame[checksumAt + 1] = (byte) (crc >>> 8);
    }

    private static ByteBuffer little(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] fixedName(String name) {
        byte[] out = new byte[10];
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, out, 0, Math.min(bytes.length, out.length));
        return out;
    }
}
