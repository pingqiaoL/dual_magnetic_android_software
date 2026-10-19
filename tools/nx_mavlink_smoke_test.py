#!/usr/bin/env python3
"""ORIN-NX UART smoke test for the MK32 coating-measurement application."""

import argparse
import os
import select
import struct
import termios
import time

CRC_EXTRA = {0: 50, 4: 237, 76: 152, 77: 143, 252: 44}
COMMANDS = {31010: "START", 31011: "STOP", 31012: "ZERO"}
NX_SYS_ID = 1
NX_COMP_ID = 191


def crc_accumulate(value, crc):
    tmp = value ^ (crc & 0xFF)
    tmp ^= (tmp << 4) & 0xFF
    return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF


class Mavlink:
    def __init__(self):
        self.sequence = 0
        self.buffer = bytearray()
        self.expected = 0

    def frame(self, message_id, payload):
        header = bytearray((0xFD, len(payload), 0, 0, self.sequence & 0xFF,
                            NX_SYS_ID, NX_COMP_ID, message_id & 0xFF,
                            (message_id >> 8) & 0xFF, (message_id >> 16) & 0xFF))
        self.sequence += 1
        packet = header + payload
        crc = 0xFFFF
        for value in packet[1:]:
            crc = crc_accumulate(value, crc)
        crc = crc_accumulate(CRC_EXTRA[message_id], crc)
        return bytes(packet + struct.pack("<H", crc))

    def accept(self, value):
        if not self.buffer:
            if value not in (0xFD, 0xFE):
                return None
            self.buffer.append(value)
            self.expected = 0
            return None
        self.buffer.append(value)
        magic = self.buffer[0]
        if magic == 0xFE and len(self.buffer) == 2:
            self.expected = self.buffer[1] + 8
        elif magic == 0xFD and len(self.buffer) == 3:
            self.expected = self.buffer[1] + 12 + (13 if self.buffer[2] & 1 else 0)
        if not self.expected or len(self.buffer) < self.expected:
            return None
        frame = bytes(self.buffer)
        self.buffer.clear()
        self.expected = 0
        if magic == 0xFE:
            message_id, payload_at, system, component = frame[5], 6, frame[3], frame[4]
        else:
            message_id = frame[7] | frame[8] << 8 | frame[9] << 16
            payload_at, system, component = 10, frame[5], frame[6]
        extra = CRC_EXTRA.get(message_id)
        if extra is None:
            return None
        checksum_at = payload_at + frame[1]
        crc = 0xFFFF
        for byte in frame[1:checksum_at]:
            crc = crc_accumulate(byte, crc)
        crc = crc_accumulate(extra, crc)
        if crc != struct.unpack_from("<H", frame, checksum_at)[0]:
            return None
        return message_id, system, component, frame[payload_at:checksum_at]


def configure_serial(fd, baud):
    speed = {57600: termios.B57600, 115200: termios.B115200}[baud]
    attrs = termios.tcgetattr(fd)
    attrs[0] = 0
    attrs[1] = 0
    attrs[2] = termios.CS8 | termios.CLOCAL | termios.CREAD
    attrs[3] = 0
    attrs[4] = speed
    attrs[5] = speed
    attrs[6][termios.VMIN] = 0
    attrs[6][termios.VTIME] = 1
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    termios.tcflush(fd, termios.TCIOFLUSH)


def heartbeat_payload(vehicle_state):
    base_mode = 0x80 if vehicle_state == "unlocked" else 0
    system_status = 5 if vehicle_state == "exception" else 4
    return struct.pack("<IBBBBB", 0, 10, 8, base_mode, system_status, 3)


def named_int_payload(name, value):
    encoded = name.encode("ascii")[:10].ljust(10, b"\0")
    return struct.pack("<Ii10s", int(time.monotonic() * 1000) & 0xFFFFFFFF, value, encoded)


def main():
    parser = argparse.ArgumentParser(description="Validate MK32 <-> ORIN-NX MAVLink UART")
    parser.add_argument("device", nargs="?", default="/dev/ttyUSB0")
    parser.add_argument("--baud", type=int, choices=(57600, 115200), default=115200)
    parser.add_argument("--vehicle-state", choices=("exception", "locked", "unlocked"), default="locked")
    args = parser.parse_args()

    fd = os.open(args.device, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    configure_serial(fd, args.baud)
    mav = Mavlink()
    measurement_running = False
    last_status = 0.0
    print(f"NX smoke test: {args.device} {args.baud} 8N1, state={args.vehicle_state}")
    print("Waiting for MK32 heartbeat/ping/commands (Ctrl+C to stop)...")
    try:
        while True:
            now = time.monotonic()
            if now - last_status >= 1.0:
                state_value = {"exception": 0, "locked": 1, "unlocked": 2}[args.vehicle_state]
                os.write(fd, mav.frame(0, heartbeat_payload(args.vehicle_state)))
                os.write(fd, mav.frame(252, named_int_payload("VEH_STATE", state_value)))
                os.write(fd, mav.frame(252, named_int_payload("STM_LINK", 1)))
                os.write(fd, mav.frame(252, named_int_payload("MEAS_RUN", int(measurement_running))))
                last_status = now
            readable, _, _ = select.select([fd], [], [], 0.1)
            if not readable:
                continue
            data = os.read(fd, 4096)
            for byte in data:
                message = mav.accept(byte)
                if message is None:
                    continue
                message_id, source_system, source_component, payload = message
                if message_id == 4 and len(payload) >= 14:
                    time_usec, ping_seq, target_system, target_component = struct.unpack("<QIBB", payload[:14])
                    if target_system == 0 and target_component == 0:
                        response = struct.pack("<QIBB", time_usec, ping_seq, source_system, source_component)
                        os.write(fd, mav.frame(4, response))
                        print(f"PING {ping_seq}: replied to {source_system}/{source_component}")
                elif message_id == 76 and len(payload) >= 33:
                    command = struct.unpack_from("<H", payload, 28)[0]
                    if command in COMMANDS:
                        if command == 31010:
                            measurement_running = True
                        elif command == 31011:
                            measurement_running = False
                        ack = struct.pack("<HB", command, 0)
                        os.write(fd, mav.frame(77, ack))
                        print(f"COMMAND {COMMANDS[command]} ({command}): ACK accepted")
                    else:
                        os.write(fd, mav.frame(77, struct.pack("<HB", command, 2)))
                        print(f"COMMAND {command}: ACK unsupported")
    except KeyboardInterrupt:
        print("\nStopped")
    finally:
        os.close(fd)


if __name__ == "__main__":
    main()
