package com.example.gateway.protocol;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 充电桩实时数据协议编解码器。
 *
 * <p>用途：负责在 {@link ChargingReport} 与 Protobuf wire 二进制帧之间转换，并校验
 * 功能码、帧方向、时间戳和 MD5 签名。业务层不需要关心 tag、wire type 等底层细节。</p>
 */
public final class ChargingProtocolCodec {
    // 题目协议约定：103 表示实时数据上报。
    public static final int REALTIME_DATA_FUNCTION = 103;
    // 协议帧最大 65535 字节，用于限制异常输入。
    public static final int MAX_FRAME_LENGTH = 65_535;

    private final String secret;

    public ChargingProtocolCodec(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Protocol secret must not be blank");
        }
        this.secret = secret;
    }

    /**
     * 将结构化上报编码为正式协议帧。
     *
     * <p>用途：设备模拟器也通过这个方法生成二进制数据，从而确保模拟入口没有绕过协议层。</p>
     */
    public byte[] encodeReport(ChargingReport report) {
        // 先编码业务 payload，再将其放入包含序号、功能码、时间戳和签名的外层 envelope。
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

    /**
     * 解析并验证一帧设备上报。
     *
     * <p>用途：把不可信的网络字节转换为领域对象。任一协议规则不满足都会抛出
     * {@link ProtocolException}，业务服务不会继续保存数据。</p>
     */
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
        // Protobuf 的字段顺序不固定，因此循环读取 tag，并按字段编号分派。
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

        // 外层协议校验：只接受实时上报请求，且必须包含时间戳、正确签名和业务数据。
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

    /** 编码业务字段：设备号、状态、电压、电流和故障码。 */
    private byte[] encodePayload(ChargingReport report) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ProtoWire.writeString(payload, 1, report.deviceSn());
        ProtoWire.writeInt32(payload, 2, report.status().code());
        ProtoWire.writeDouble(payload, 3, report.voltage());
        ProtoWire.writeDouble(payload, 4, report.current());
        ProtoWire.writeString(payload, 5, report.faultCode() == null ? "" : report.faultCode());
        return payload.toByteArray();
    }

    /** 解析业务 payload，并检查所有必填字段是否存在。 */
    private ChargingReport decodePayload(byte[] payload, long timestamp, int sequence) {
        String sn = null;
        ChargingStatus status = null;
        double voltage = Double.NaN;
        double current = Double.NaN;
        String faultCode = "";
        ProtoWire.Reader reader = new ProtoWire.Reader(payload);
        // 未知字段会被跳过，便于协议未来增加字段时保持向前兼容。
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

    /**
     * 按题目约定计算 MD5(secret + "_" + timestamp) 签名。
     * 注意：MD5 仅用于兼容本题协议，生产系统应使用 HMAC-SHA256 等更强方案。
     */
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
