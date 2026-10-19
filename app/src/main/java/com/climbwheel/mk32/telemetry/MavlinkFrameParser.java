package com.climbwheel.mk32.telemetry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Incremental MAVLink 1/2 frame parser for the common messages used by this app. */
public final class MavlinkFrameParser {
    private static final int MAVLINK_1_MAGIC = 0xFE;
    private static final int MAVLINK_2_MAGIC = 0xFD;
    private static final int MAVLINK_2_SIGNED = 0x01;
    private static final int MAX_FRAME = 280;
    private static final Map<Integer, Integer> CRC_EXTRAS = new HashMap<>();

    static {
        CRC_EXTRAS.put(0, 50);    // HEARTBEAT
        CRC_EXTRAS.put(1, 124);   // SYS_STATUS
        CRC_EXTRAS.put(4, 237);   // PING
        CRC_EXTRAS.put(32, 185);  // LOCAL_POSITION_NED
        CRC_EXTRAS.put(74, 20);   // VFR_HUD
        CRC_EXTRAS.put(76, 152);  // COMMAND_LONG
        CRC_EXTRAS.put(77, 143);  // COMMAND_ACK
        CRC_EXTRAS.put(147, 154); // BATTERY_STATUS
        CRC_EXTRAS.put(251, 170); // NAMED_VALUE_FLOAT
        CRC_EXTRAS.put(252, 44);  // NAMED_VALUE_INT
        CRC_EXTRAS.put(253, 83);  // STATUSTEXT
        CRC_EXTRAS.put(350, 232); // DEBUG_FLOAT_ARRAY (MAVLink 2)
    }

    private final byte[] frame = new byte[MAX_FRAME];
    private int size;
    private int expected;

    /** Returns a message when a complete valid known frame arrives, otherwise null. */
    public MavlinkMessage accept(int unsignedByte) {
        int value = unsignedByte & 0xff;
        if (size == 0) {
            if (value != MAVLINK_1_MAGIC && value != MAVLINK_2_MAGIC) return null;
            frame[size++] = (byte) value;
            expected = 0;
            return null;
        }

        if (size >= MAX_FRAME) {
            reset();
            return restartIfMagic(value);
        }
        frame[size++] = (byte) value;
        int magic = frame[0] & 0xff;
        if (size == 2 && magic == MAVLINK_1_MAGIC) {
            expected = (frame[1] & 0xff) + 8;
        } else if (size == 3 && magic == MAVLINK_2_MAGIC) {
            int signature = ((frame[2] & MAVLINK_2_SIGNED) != 0) ? 13 : 0;
            expected = (frame[1] & 0xff) + 12 + signature;
        }

        if (expected > 0 && size == expected) {
            MavlinkMessage message = decodeCompleteFrame();
            reset();
            return message;
        }
        if (expected > MAX_FRAME || (expected > 0 && size > expected)) {
            reset();
            return restartIfMagic(value);
        }
        return null;
    }

    private MavlinkMessage decodeCompleteFrame() {
        int magic = frame[0] & 0xff;
        int payloadLength = frame[1] & 0xff;
        int messageId;
        int payloadStart;
        int sequence;
        int systemId;
        int componentId;
        if (magic == MAVLINK_1_MAGIC) {
            sequence = frame[2] & 0xff;
            systemId = frame[3] & 0xff;
            componentId = frame[4] & 0xff;
            messageId = frame[5] & 0xff;
            payloadStart = 6;
        } else {
            sequence = frame[4] & 0xff;
            systemId = frame[5] & 0xff;
            componentId = frame[6] & 0xff;
            messageId = (frame[7] & 0xff) | ((frame[8] & 0xff) << 8) | ((frame[9] & 0xff) << 16);
            payloadStart = 10;
        }
        Integer crcExtra = CRC_EXTRAS.get(messageId);
        if (crcExtra == null) return null;
        int checksumAt = payloadStart + payloadLength;
        int expectedChecksum = (frame[checksumAt] & 0xff) | ((frame[checksumAt + 1] & 0xff) << 8);
        int calculated = 0xffff;
        for (int i = 1; i < checksumAt; i++) calculated = crcAccumulate(frame[i] & 0xff, calculated);
        calculated = crcAccumulate(crcExtra, calculated);
        if (calculated != expectedChecksum) return null;
        return new MavlinkMessage(magic == MAVLINK_1_MAGIC ? 1 : 2, sequence, systemId,
                componentId, messageId, Arrays.copyOfRange(frame, payloadStart, checksumAt));
    }

    static int crcAccumulate(int value, int crc) {
        int tmp = value ^ (crc & 0xff);
        tmp ^= (tmp << 4) & 0xff;
        return ((crc >>> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >>> 4)) & 0xffff;
    }

    private MavlinkMessage restartIfMagic(int value) {
        if (value == MAVLINK_1_MAGIC || value == MAVLINK_2_MAGIC) {
            frame[0] = (byte) value;
            size = 1;
        }
        return null;
    }

    private void reset() { size = 0; expected = 0; }
}
