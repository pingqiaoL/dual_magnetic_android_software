package com.climbwheel.mk32.telemetry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal MAVLink 2 encoder for the MK32 GCS heartbeat, ping and coating commands. */
public final class MavlinkEncoder {
    public static final int GCS_SYSTEM_ID = 255;
    public static final int GCS_COMPONENT_ID = 190;
    public static final int NX_SYSTEM_ID = 1;
    public static final int NX_COMPONENT_ID = 191;

    private MavlinkEncoder() { }

    public static byte[] heartbeat(int sequence) {
        ByteBuffer p = little(9);
        p.putInt(0);       // custom_mode
        p.put((byte) 6);   // MAV_TYPE_GCS
        p.put((byte) 8);   // MAV_AUTOPILOT_INVALID
        p.put((byte) 0);   // base_mode
        p.put((byte) 4);   // MAV_STATE_ACTIVE
        p.put((byte) 3);   // mavlink_version
        return frame(sequence, 0, 50, p.array());
    }

    public static byte[] ping(int sequence, int pingSequence, long timeUsec) {
        return ping(sequence, pingSequence, timeUsec, 0, 0);
    }

    public static byte[] ping(int sequence, int pingSequence, long timeUsec,
                              int targetSystem, int targetComponent) {
        ByteBuffer p = little(14);
        p.putLong(timeUsec).putInt(pingSequence)
                .put((byte) targetSystem).put((byte) targetComponent);
        return frame(sequence, 4, 237, p.array());
    }

    public static byte[] commandLong(int sequence, int command, int confirmation) {
        ByteBuffer p = little(33);
        for (int i = 0; i < 7; i++) p.putFloat(0f);
        p.putShort((short) command);
        p.put((byte) NX_SYSTEM_ID).put((byte) NX_COMPONENT_ID).put((byte) confirmation);
        return frame(sequence, 76, 152, p.array());
    }

    private static byte[] frame(int sequence, int messageId, int crcExtra, byte[] payload) {
        byte[] out = new byte[payload.length + 12];
        out[0] = (byte) 0xFD;
        out[1] = (byte) payload.length;
        out[2] = 0;
        out[3] = 0;
        out[4] = (byte) sequence;
        out[5] = (byte) GCS_SYSTEM_ID;
        out[6] = (byte) GCS_COMPONENT_ID;
        out[7] = (byte) messageId;
        out[8] = (byte) (messageId >>> 8);
        out[9] = (byte) (messageId >>> 16);
        System.arraycopy(payload, 0, out, 10, payload.length);
        int checksumAt = 10 + payload.length;
        int crc = 0xffff;
        for (int i = 1; i < checksumAt; i++) crc = MavlinkFrameParser.crcAccumulate(out[i] & 0xff, crc);
        crc = MavlinkFrameParser.crcAccumulate(crcExtra, crc);
        out[checksumAt] = (byte) crc;
        out[checksumAt + 1] = (byte) (crc >>> 8);
        return out;
    }

    private static ByteBuffer little(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
