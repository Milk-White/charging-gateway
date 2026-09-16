package com.example.gateway.protocol;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public final class ChargingProtocolCodec {
    public static final int REALTIME_DATA_FUNCTION = 103;
    public static final int MAX_FRAME_LENGTH = 65_535;

    private final String secret;

    public ChargingProtocolCodec(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Protocol secret must not be blank");
        }
        this.secret = secret;
    }

    public byte[] encodeReport(ChargingReport report) {
        byte[] payload = encodePayload(report);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        ProtoWire.writeInt32(envelope, 1, report.packageSequence());
        ProtoWire.writeInt32(envelope, 2, REALTIME_DATA_FUNCTION);
        ProtoWire.writeInt32(envelope, 3, Math.toIntExact(report.protocolTimestamp()));
        ProtoWire.writeBool(envelope, 4, false);
        ProtoWire.writeBytes(envelope, 10, payload);
        ProtoWire.writeString(envelope, 11, signature(report.protocolTimestamp()));
        byte[] frame = envelope.toByteArray();
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }
        return frame;
    }

    public ChargingReport decodeReport(byte[] frame) {
        if (frame == null || frame.length == 0) {
            throw new ProtocolException("Frame is empty");
        }
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }

        int sequence = 0;
        int functionCode = 0;
        long timestamp = 0;
        boolean response = false;
        byte[] data = null;
        String signature = "";
        ProtoWire.Reader reader = new ProtoWire.Reader(frame);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> sequence = reader.readInt32();
                case 2 -> functionCode = reader.readInt32();
                case 3 -> timestamp = Integer.toUnsignedLong(reader.readInt32());
                case 4 -> response = reader.readBool();
                case 10 -> data = reader.readBytes();
                case 11 -> signature = reader.readString();
                default -> reader.skip(wire);
            }
        }

        if (functionCode != REALTIME_DATA_FUNCTION) {
            throw new ProtocolException("Unsupported function code: " + functionCode);
        }
        if (response) {
            throw new ProtocolException("A response frame cannot be accepted as an upload");
        }
        if (timestamp == 0) {
            throw new ProtocolException("Protocol timestamp is required");
        }
        if (!MessageDigest.isEqual(
                signature(timestamp).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("Invalid frame signature");
        }
        if (data == null) {
            throw new ProtocolException("Business payload is required");
        }
        return decodePayload(data, timestamp, sequence);
    }

    private byte[] encodePayload(ChargingReport report) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ProtoWire.writeString(payload, 1, report.deviceSn());
        ProtoWire.writeInt32(payload, 2, report.status().code());
        ProtoWire.writeDouble(payload, 3, report.voltage());
        ProtoWire.writeDouble(payload, 4, report.current());
        ProtoWire.writeString(payload, 5, report.faultCode() == null ? "" : report.faultCode());
        return payload.toByteArray();
    }

    private ChargingReport decodePayload(byte[] payload, long timestamp, int sequence) {
        String sn = null;
        ChargingStatus status = null;
        double voltage = Double.NaN;
        double current = Double.NaN;
        String faultCode = "";
        ProtoWire.Reader reader = new ProtoWire.Reader(payload);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> sn = reader.readString();
                case 2 -> {
                    try {
                        status = ChargingStatus.fromCode(reader.readInt32());
                    } catch (IllegalArgumentException e) {
                        throw new ProtocolException(e.getMessage(), e);
                    }
                }
                case 3 -> voltage = reader.readDouble();
                case 4 -> current = reader.readDouble();
                case 5 -> faultCode = reader.readString();
                default -> reader.skip(wire);
            }
        }
        if (sn == null || status == null || Double.isNaN(voltage) || Double.isNaN(current)) {
            throw new ProtocolException("Payload misses a required field");
        }
        return new ChargingReport(sn, status, voltage, current, faultCode,
                timestamp, sequence, Instant.now().toEpochMilli());
    }

    private String signature(long timestamp) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((secret + "_" + timestamp).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
