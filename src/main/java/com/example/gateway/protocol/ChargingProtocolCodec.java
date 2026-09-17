package com.example.gateway.protocol;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.domain.LoginRequest;
import com.example.gateway.domain.RealtimePush;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** 充电桩协议外层帧编解码器，支持登录 101、心跳 102 和实时数据 103。 */
public final class ChargingProtocolCodec {
    public static final int LOGIN_FUNCTION = 101;
    public static final int HEARTBEAT_FUNCTION = 102;
    public static final int REALTIME_DATA_FUNCTION = 103;
    public static final int MAX_FRAME_LENGTH = 65_535;

    private final String secret;

    public ChargingProtocolCodec(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Protocol secret must not be blank");
        }
        this.secret = secret;
    }

    /** 生成 101 登录请求。文档示例字段号重复，本实现修正为 UserName=1、Pwd=2。 */
    public byte[] encodeLoginRequest(int sequence, long timestamp, LoginRequest request) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ProtoWire.writeString(data, 1, request.userName());
        ProtoWire.writeString(data, 2, request.password());
        return encodeRequest(sequence, LOGIN_FUNCTION, timestamp, data.toByteArray());
    }

    public LoginRequest decodeLoginRequest(byte[] frame) {
        ProtocolEnvelope envelope = requireRequest(frame, LOGIN_FUNCTION, true);
        String userName = null;
        String password = null;
        ProtoWire.Reader reader = new ProtoWire.Reader(envelope.data());
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                userName = reader.readString();
            } else if (field == 2) {
                requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                password = reader.readString();
            } else {
                reader.skip(wire);
            }
        }
        if (userName == null || password == null) {
            throw new ProtocolException("Login payload misses UserName or Pwd");
        }
        try {
            return new LoginRequest(userName, password);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage(), exception);
        }
    }

    public byte[] encodeHeartbeatRequest(int sequence, long timestamp) {
        return encodeRequest(sequence, HEARTBEAT_FUNCTION, timestamp, new byte[0]);
    }

    public byte[] encodeRealtimeRequest(int sequence, long timestamp, RealtimePush push,
                                        ChargingValueCodec valueCodec) {
        return encodeRequest(sequence, REALTIME_DATA_FUNCTION, timestamp, valueCodec.encode(push));
    }

    /** 对 101/102/103 请求生成同序号、同功能码的正式响应帧。 */
    public byte[] encodeResponse(ProtocolEnvelope request, int resultCode, String resultMessage) {
        return encodeEnvelope(request.packageSequence(), request.functionCode(),
                Instant.now().getEpochSecond(), true, resultCode, resultMessage, new byte[0]);
    }

    public ProtocolEnvelope decodeResponse(byte[] frame, int expectedFunctionCode) {
        ProtocolEnvelope envelope = decodeEnvelope(frame);
        if (!envelope.response()) {
            throw new ProtocolException("Expected a response frame");
        }
        if (envelope.functionCode() != expectedFunctionCode) {
            throw new ProtocolException("Unexpected response function code: " + envelope.functionCode());
        }
        return envelope;
    }

    /** 兼容网页和旧脚本：把摘要数据编码为 103 请求。 */
    public byte[] encodeReport(ChargingReport report) {
        return encodeRequest(report.packageSequence(), REALTIME_DATA_FUNCTION,
                report.protocolTimestamp(), encodeLegacyPayload(report));
    }

    /** 兼容旧接口：解析摘要格式 103 请求。 */
    public ChargingReport decodeReport(byte[] frame) {
        ProtocolEnvelope envelope = requireRequest(frame, REALTIME_DATA_FUNCTION, true);
        return decodeLegacyPayload(envelope.data(), envelope.timestamp(), envelope.packageSequence());
    }

    /** 解码并验证公共协议头、最大长度、必填字段、时间戳和 MD5 签名。 */
    public ProtocolEnvelope decodeEnvelope(byte[] frame) {
        if (frame == null || frame.length == 0) {
            throw new ProtocolException("Frame is empty");
        }
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }
        int sequence = 0;
        boolean sequencePresent = false;
        int functionCode = 0;
        long timestamp = 0;
        boolean response = false;
        boolean responsePresent = false;
        int resultCode = 0;
        String resultMessage = "";
        byte[] data = new byte[0];
        String frameSignature = "";
        ProtoWire.Reader reader = new ProtoWire.Reader(frame);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> { requireWireType(field, wire, ProtoWire.VARINT); sequence = reader.readInt32(); sequencePresent = true; }
                case 2 -> { requireWireType(field, wire, ProtoWire.VARINT); functionCode = reader.readInt32(); }
                case 3 -> { requireWireType(field, wire, ProtoWire.VARINT); timestamp = Integer.toUnsignedLong(reader.readInt32()); }
                case 4 -> { requireWireType(field, wire, ProtoWire.VARINT); response = reader.readBool(); responsePresent = true; }
                case 5 -> { requireWireType(field, wire, ProtoWire.VARINT); resultCode = reader.readInt32(); }
                case 6 -> { requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED); resultMessage = reader.readString(); }
                case 10 -> { requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED); data = reader.readBytes(); }
                case 11 -> { requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED); frameSignature = reader.readString(); }
                default -> reader.skip(wire);
            }
        }
        if (!sequencePresent || functionCode == 0 || !responsePresent || timestamp == 0) {
            throw new ProtocolException("Envelope misses sequence, function, direction, or timestamp");
        }
        if (!MessageDigest.isEqual(signature(timestamp).getBytes(StandardCharsets.US_ASCII),
                frameSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("Invalid frame signature");
        }
        return new ProtocolEnvelope(sequence, functionCode, timestamp, response,
                resultCode, resultMessage, data);
    }

    private byte[] encodeRequest(int sequence, int functionCode, long timestamp, byte[] data) {
        return encodeEnvelope(sequence, functionCode, timestamp, false, 0, "", data);
    }

    private byte[] encodeEnvelope(int sequence, int functionCode, long timestamp, boolean response,
                                  int resultCode, String resultMessage, byte[] data) {
        if (timestamp < 1 || timestamp > 0xffff_ffffL) {
            throw new ProtocolException("Timestamp is outside uint32 range");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtoWire.writeInt32(out, 1, sequence);
        ProtoWire.writeInt32(out, 2, functionCode);
        ProtoWire.writeInt32(out, 3, (int) timestamp);
        ProtoWire.writeBool(out, 4, response);
        if (response) {
            ProtoWire.writeInt32(out, 5, resultCode);
            ProtoWire.writeString(out, 6, resultMessage == null ? "" : resultMessage);
        }
        if (data != null && data.length > 0) {
            ProtoWire.writeBytes(out, 10, data);
        }
        ProtoWire.writeString(out, 11, signature(timestamp));
        byte[] frame = out.toByteArray();
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }
        return frame;
    }

    private ProtocolEnvelope requireRequest(byte[] frame, int expectedFunctionCode, boolean requireData) {
        ProtocolEnvelope envelope = decodeEnvelope(frame);
        if (envelope.response()) {
            throw new ProtocolException("A response frame cannot be accepted as a request");
        }
        if (envelope.functionCode() != expectedFunctionCode) {
            throw new ProtocolException("Unsupported function code: " + envelope.functionCode());
        }
        if (requireData && envelope.data().length == 0) {
            throw new ProtocolException("Business payload is required");
        }
        return envelope;
    }

    private byte[] encodeLegacyPayload(ChargingReport report) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ProtoWire.writeString(payload, 1, report.deviceSn());
        ProtoWire.writeInt32(payload, 2, report.status().code());
        ProtoWire.writeDouble(payload, 3, report.voltage());
        ProtoWire.writeDouble(payload, 4, report.current());
        ProtoWire.writeString(payload, 5, report.faultCode() == null ? "" : report.faultCode());
        return payload.toByteArray();
    }

    private ChargingReport decodeLegacyPayload(byte[] payload, long timestamp, int sequence) {
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
                case 1 -> { requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED); sn = reader.readString(); }
                case 2 -> {
                    requireWireType(field, wire, ProtoWire.VARINT);
                    try { status = ChargingStatus.fromCode(reader.readInt32()); }
                    catch (IllegalArgumentException exception) { throw new ProtocolException(exception.getMessage(), exception); }
                }
                case 3 -> { requireWireType(field, wire, ProtoWire.FIXED64); voltage = reader.readDouble(); }
                case 4 -> { requireWireType(field, wire, ProtoWire.FIXED64); current = reader.readDouble(); }
                case 5 -> { requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED); faultCode = reader.readString(); }
                default -> reader.skip(wire);
            }
        }
        if (sn == null || status == null || Double.isNaN(voltage) || Double.isNaN(current)) {
            throw new ProtocolException("Payload misses a required field");
        }
        return new ChargingReport(sn, status, voltage, current, faultCode,
                timestamp, sequence, Instant.now().toEpochMilli());
    }

    private static void requireWireType(int field, int actual, int expected) {
        if (actual != expected) {
            throw new ProtocolException("Invalid wire type for field " + field
                    + ": expected " + expected + " but was " + actual);
        }
    }

    private String signature(long timestamp) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((secret + "_" + timestamp).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
