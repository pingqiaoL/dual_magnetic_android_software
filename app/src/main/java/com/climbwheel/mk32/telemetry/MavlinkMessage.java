package com.climbwheel.mk32.telemetry;

/** A CRC-validated MAVLink 1 or MAVLink 2 frame. */
public final class MavlinkMessage {
    public final int version;
    public final int sequence;
    public final int systemId;
    public final int componentId;
    public final int messageId;
    public final byte[] payload;

    MavlinkMessage(int version, int sequence, int systemId, int componentId,
                   int messageId, byte[] payload) {
        this.version = version;
        this.sequence = sequence;
        this.systemId = systemId;
        this.componentId = componentId;
        this.messageId = messageId;
        this.payload = payload;
    }
}

