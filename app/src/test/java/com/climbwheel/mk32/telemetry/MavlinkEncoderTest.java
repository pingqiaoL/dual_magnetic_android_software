package com.climbwheel.mk32.telemetry;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class MavlinkEncoderTest {
    @Test public void commandLongIsValidAndTargetsNx() {
        MavlinkMessage message = parse(MavlinkEncoder.commandLong(9, 31010, 0));
        assertNotNull(message);
        assertEquals(2, message.version);
        assertEquals(76, message.messageId);
        assertEquals(MavlinkEncoder.GCS_SYSTEM_ID, message.systemId);
        assertEquals(MavlinkEncoder.GCS_COMPONENT_ID, message.componentId);
        ByteBuffer p = ByteBuffer.wrap(message.payload).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(31010, p.getShort(28) & 0xffff);
        assertEquals(MavlinkEncoder.NX_SYSTEM_ID, message.payload[30] & 0xff);
        assertEquals(MavlinkEncoder.NX_COMPONENT_ID, message.payload[31] & 0xff);
    }

    @Test public void heartbeatAndPingHaveValidCrc() {
        assertEquals(0, parse(MavlinkEncoder.heartbeat(1)).messageId);
        assertEquals(4, parse(MavlinkEncoder.ping(2, 17, 1000L)).messageId);
    }

    @Test public void decoderReportsCommandAck() {
        AtomicInteger command = new AtomicInteger();
        AtomicInteger result = new AtomicInteger(-1);
        MavlinkTelemetryDecoder decoder = new MavlinkTelemetryDecoder(new MavlinkTelemetryDecoder.ProtocolListener() {
            @Override public void onCommandAck(int value, int ackResult) {
                command.set(value); result.set(ackResult);
            }
            @Override public void onPingRequest(long timeUsec, long sequence, int system, int component) { }
            @Override public void onPingResponse(long timeUsec, long sequence) { }
        });
        ByteBuffer payload = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short) 31011).put((byte) 0);
        byte[] ack = mav2(77, 143, payload.array());
        for (byte value : ack) decoder.accept(value & 0xff);
        assertEquals(31011, command.get());
        assertEquals(0, result.get());
    }

    private static MavlinkMessage parse(byte[] frame) {
        MavlinkFrameParser parser = new MavlinkFrameParser();
        MavlinkMessage message = null;
        for (byte value : frame) {
            MavlinkMessage next = parser.accept(value & 0xff);
            if (next != null) message = next;
        }
        return message;
    }

    private static byte[] mav2(int messageId, int crcExtra, byte[] payload) {
        byte[] frame = new byte[payload.length + 12];
        frame[0] = (byte) 0xFD; frame[1] = (byte) payload.length;
        frame[4] = 1; frame[5] = 1; frame[6] = (byte) 191;
        frame[7] = (byte) messageId; frame[8] = (byte) (messageId >>> 8); frame[9] = (byte) (messageId >>> 16);
        System.arraycopy(payload, 0, frame, 10, payload.length);
        int checksumAt = 10 + payload.length;
        int crc = 0xffff;
        for (int i = 1; i < checksumAt; i++) crc = MavlinkFrameParser.crcAccumulate(frame[i] & 0xff, crc);
        crc = MavlinkFrameParser.crcAccumulate(crcExtra, crc);
        frame[checksumAt] = (byte) crc;
        frame[checksumAt + 1] = (byte) (crc >>> 8);
        return frame;
    }
}
