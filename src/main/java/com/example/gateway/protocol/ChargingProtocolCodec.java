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
    // 功能码 101：设备登录。
    public static final int LOGIN_FUNCTION = 101;
    // 功能码 102：设备心跳。
    public static final int HEARTBEAT_FUNCTION = 102;
    // 功能码 103：实时数据推送。
    public static final int REALTIME_DATA_FUNCTION = 103;
    // 工作任务规定单帧最大 65535 字节，编码和解码都执行同一限制。
    public static final int MAX_FRAME_LENGTH = 65_535;

    // secret 是平台与设备共享的签名密钥，不会直接写入协议帧。
    private final String secret;

    public ChargingProtocolCodec(String secret) {
        // 空密钥无法提供任何签名校验意义，因此启动时立即拒绝。
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Protocol secret must not be blank");
        }
        // 保存密钥，后续每个请求和响应都用它计算 MD5。
        this.secret = secret;
    }

    /** 生成 101 登录请求。文档示例字段号重复，本实现修正为 UserName=1、Pwd=2。 */
    public byte[] encodeLoginRequest(int sequence, long timestamp, LoginRequest request) {
        // 101 的 Data 是一个嵌套消息，先单独构造它。
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // 字段 1 保存用户名，本项目要求它等于设备 SN。
        ProtoWire.writeString(data, 1, request.userName());
        // 字段 2 保存密码；工作任务示例字段号重复，这里修正为合法编号。
        ProtoWire.writeString(data, 2, request.password());
        // 把登录 Data 包装进功能码 101 的公共请求帧。
        return encodeRequest(sequence, LOGIN_FUNCTION, timestamp, data.toByteArray());
    }

    public LoginRequest decodeLoginRequest(byte[] frame) {
        // 先验证它确实是带业务数据的 101 请求，而不是响应或其他功能码。
        ProtocolEnvelope envelope = requireRequest(frame, LOGIN_FUNCTION, true);
        // null 表示对应必填字段尚未出现。
        String userName = null;
        String password = null;
        // 只解析外层帧的 Data 字段。
        ProtoWire.Reader reader = new ProtoWire.Reader(envelope.data());
        while (reader.hasNext()) {
            // 每轮读取一个字段 tag，并拆出字段编号和 wire type。
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                // 用户名是字符串，所以必须是 length-delimited。
                requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                // 按 UTF-8 读取用户名。
                userName = reader.readString();
            } else if (field == 2) {
                // 密码同样是字符串。
                requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                // 读取密码，真正的凭据校验由 DeviceSessionService 执行。
                password = reader.readString();
            } else {
                // 跳过未来扩展字段，保持协议兼容。
                reader.skip(wire);
            }
        }
        // 两个字段缺少任意一个都不能尝试登录。
        if (userName == null || password == null) {
            throw new ProtocolException("Login payload misses UserName or Pwd");
        }
        try {
            // 构造领域对象，它还会检查空用户名或空密码。
            return new LoginRequest(userName, password);
        } catch (IllegalArgumentException exception) {
            // 把领域参数异常包装成协议异常，便于上层返回统一结果码。
            throw new ProtocolException(exception.getMessage(), exception);
        }
    }

    public byte[] encodeHeartbeatRequest(int sequence, long timestamp) {
        // 102 没有业务 Data，因此传入长度为 0 的数组。
        return encodeRequest(sequence, HEARTBEAT_FUNCTION, timestamp, new byte[0]);
    }

    public byte[] encodeRealtimeRequest(int sequence, long timestamp, RealtimePush push,
                                        ChargingValueCodec valueCodec) {
        // 先由 valueCodec 编码枪和点位，再把结果包装成功能码 103 的外层请求。
        return encodeRequest(sequence, REALTIME_DATA_FUNCTION, timestamp, valueCodec.encode(push));
    }

    /** 对 101/102/103 请求生成同序号、同功能码的正式响应帧。 */
    public byte[] encodeResponse(ProtocolEnvelope request, int resultCode, String resultMessage) {
        // 响应沿用请求序号和功能码；时间改为平台当前秒；IsResponse 设为 true。
        return encodeEnvelope(request.packageSequence(), request.functionCode(),
                Instant.now().getEpochSecond(), true, resultCode, resultMessage, new byte[0]);
    }

    public ProtocolEnvelope decodeResponse(byte[] frame, int expectedFunctionCode) {
        // 公共解析会同时验证长度、必填字段和签名。
        ProtocolEnvelope envelope = decodeEnvelope(frame);
        // 调用者期待响应时，IsResponse=false 属于方向错误。
        if (!envelope.response()) {
            throw new ProtocolException("Expected a response frame");
        }
        // 登录请求只能接受 101 响应，避免把其他响应误配给当前请求。
        if (envelope.functionCode() != expectedFunctionCode) {
            throw new ProtocolException("Unexpected response function code: " + envelope.functionCode());
        }
        // 返回已通过验证的结构化外层帧。
        return envelope;
    }

    /** 兼容网页和旧脚本：把摘要数据编码为 103 请求。 */
    public byte[] encodeReport(ChargingReport report) {
        // 旧网页接口使用摘要格式 Data，但外层仍然是标准功能码 103 请求。
        return encodeRequest(report.packageSequence(), REALTIME_DATA_FUNCTION,
                report.protocolTimestamp(), encodeLegacyPayload(report));
    }

    /** 兼容旧接口：解析摘要格式 103 请求。 */
    public ChargingReport decodeReport(byte[] frame) {
        // 兼容接口同样先验证它是带 Data 的 103 请求。
        ProtocolEnvelope envelope = requireRequest(frame, REALTIME_DATA_FUNCTION, true);
        // 用外层时间戳和帧序号补充摘要对象。
        return decodeLegacyPayload(envelope.data(), envelope.timestamp(), envelope.packageSequence());
    }

    /** 解码并验证公共协议头、最大长度、必填字段、时间戳和 MD5 签名。 */
    public ProtocolEnvelope decodeEnvelope(byte[] frame) {
        // null 和零长度都不包含任何可解析字段。
        if (frame == null || frame.length == 0) {
            throw new ProtocolException("Frame is empty");
        }
        // 在创建 Reader 前限制总长度，避免超大报文消耗过多内存。
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }
        // 以下变量保存解析到的字段值；额外布尔标记用来区分“值为 0”和“字段缺失”。
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
        // Reader 会防御性复制原始数组，并负责边界检查。
        ProtoWire.Reader reader = new ProtoWire.Reader(frame);
        while (reader.hasNext()) {
            // 一个 tag = 字段编号左移 3 位 + wire type。
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> {
                    // 字段 1：帧序号，用于请求与响应配对。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    sequence = reader.readInt32();
                    sequencePresent = true;
                }
                case 2 -> {
                    // 字段 2：功能码 101、102 或 103。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    functionCode = reader.readInt32();
                }
                case 3 -> {
                    // 字段 3：uint32 UNIX 秒时间戳，读取后转成正数 long。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    timestamp = Integer.toUnsignedLong(reader.readInt32());
                }
                case 4 -> {
                    // 字段 4：false 表示请求，true 表示响应。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    response = reader.readBool();
                    responsePresent = true;
                }
                case 5 -> {
                    // 字段 5：响应结果码，请求帧通常没有该字段。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    resultCode = reader.readInt32();
                }
                case 6 -> {
                    // 字段 6：响应结果消息。
                    requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                    resultMessage = reader.readString();
                }
                case 10 -> {
                    // 字段 10：登录或实时数据业务载荷。
                    requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                    data = reader.readBytes();
                }
                case 11 -> {
                    // 字段 11：MD5 十六进制签名字符串。
                    requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                    frameSignature = reader.readString();
                }
                // 未认识的字段按 wire type 跳过，以兼容未来扩展。
                default -> reader.skip(wire);
            }
        }
        // 序号允许为 0，所以用 sequencePresent 判断；其余公共字段必须有合法非零值。
        if (!sequencePresent || functionCode == 0 || !responsePresent || timestamp == 0) {
            throw new ProtocolException("Envelope misses sequence, function, direction, or timestamp");
        }
        // MessageDigest.isEqual 使用近似恒定时间比较，减少逐字符提前退出造成的差异。
        if (!MessageDigest.isEqual(signature(timestamp).getBytes(StandardCharsets.US_ASCII),
                frameSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("Invalid frame signature");
        }
        // 只有全部公共校验通过后才把帧交给业务服务。
        return new ProtocolEnvelope(sequence, functionCode, timestamp, response,
                resultCode, resultMessage, data);
    }

    private byte[] encodeRequest(int sequence, int functionCode, long timestamp, byte[] data) {
        // 请求统一设置 response=false、结果码 0 和空结果消息。
        return encodeEnvelope(sequence, functionCode, timestamp, false, 0, "", data);
    }

    private byte[] encodeEnvelope(int sequence, int functionCode, long timestamp, boolean response,
                                  int resultCode, String resultMessage, byte[] data) {
        // 协议时间字段是 uint32，因此必须在 1 到 0xffffffff 范围内。
        if (timestamp < 1 || timestamp > 0xffff_ffffL) {
            throw new ProtocolException("Timestamp is outside uint32 range");
        }
        // 创建最终外层帧输出流。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 字段 1：帧序号。
        ProtoWire.writeInt32(out, 1, sequence);
        // 字段 2：功能码。
        ProtoWire.writeInt32(out, 2, functionCode);
        // 字段 3：秒级时间戳；强转 int 只保留 uint32 位模式。
        ProtoWire.writeInt32(out, 3, (int) timestamp);
        // 字段 4：请求/响应方向。
        ProtoWire.writeBool(out, 4, response);
        // 结果码和消息只出现在响应中，避免请求携带无意义结果字段。
        if (response) {
            ProtoWire.writeInt32(out, 5, resultCode);
            // null 消息转为空字符串，防止 writeString 发生空指针异常。
            ProtoWire.writeString(out, 6, resultMessage == null ? "" : resultMessage);
        }
        // 只有非空业务数据才写字段 10；102 心跳和普通响应可以没有 Data。
        if (data != null && data.length > 0) {
            ProtoWire.writeBytes(out, 10, data);
        }
        // 最后写字段 11，签名只依赖共享密钥和本帧时间戳。
        ProtoWire.writeString(out, 11, signature(timestamp));
        // 取得最终不可变字节快照。
        byte[] frame = out.toByteArray();
        // 编码结束后再次检查总长度，保证自己也不会生成超限帧。
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new ProtocolException("Frame length exceeds 65535 bytes");
        }
        // 返回可通过 HTTP Base64 或 MQTT 直接发送的协议帧。
        return frame;
    }

    private ProtocolEnvelope requireRequest(byte[] frame, int expectedFunctionCode, boolean requireData) {
        // 先复用公共解码和签名校验。
        ProtocolEnvelope envelope = decodeEnvelope(frame);
        // 这里用于处理请求，所以 IsResponse=true 必须拒绝。
        if (envelope.response()) {
            throw new ProtocolException("A response frame cannot be accepted as a request");
        }
        // 功能码必须与调用方法期待的类型一致。
        if (envelope.functionCode() != expectedFunctionCode) {
            throw new ProtocolException("Unsupported function code: " + envelope.functionCode());
        }
        // 登录和 103 需要 Data；102 心跳可以把 requireData 设为 false。
        if (requireData && envelope.data().length == 0) {
            throw new ProtocolException("Business payload is required");
        }
        // 返回已经满足方向、功能码和 Data 要求的外层帧。
        return envelope;
    }

    private byte[] encodeLegacyPayload(ChargingReport report) {
        // 兼容摘要使用独立输出流，不影响正式枪/点位模型。
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        // 字段 1：设备编号。
        ProtoWire.writeString(payload, 1, report.deviceSn());
        // 字段 2：状态枚举数字代码。
        ProtoWire.writeInt32(payload, 2, report.status().code());
        // 字段 3：电压 double。
        ProtoWire.writeDouble(payload, 3, report.voltage());
        // 字段 4：电流 double。
        ProtoWire.writeDouble(payload, 4, report.current());
        // 字段 5：故障码；无故障时写空字符串。
        ProtoWire.writeString(payload, 5, report.faultCode() == null ? "" : report.faultCode());
        // 返回兼容格式业务载荷。
        return payload.toByteArray();
    }

    private ChargingReport decodeLegacyPayload(byte[] payload, long timestamp, int sequence) {
        // 使用无法和合法值混淆的初始值，解析后据此判断字段是否缺失。
        String sn = null;
        ChargingStatus status = null;
        double voltage = Double.NaN;
        double current = Double.NaN;
        String faultCode = "";
        // 创建兼容摘要载荷读取器。
        ProtoWire.Reader reader = new ProtoWire.Reader(payload);
        while (reader.hasNext()) {
            // 逐字段读取 tag。
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> {
                    // 设备编号字符串。
                    requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                    sn = reader.readString();
                }
                case 2 -> {
                    // 状态数字代码。
                    requireWireType(field, wire, ProtoWire.VARINT);
                    try {
                        status = ChargingStatus.fromCode(reader.readInt32());
                    } catch (IllegalArgumentException exception) {
                        // 未定义状态代码转成协议异常。
                        throw new ProtocolException(exception.getMessage(), exception);
                    }
                }
                case 3 -> {
                    // 电压 double。
                    requireWireType(field, wire, ProtoWire.FIXED64);
                    voltage = reader.readDouble();
                }
                case 4 -> {
                    // 电流 double。
                    requireWireType(field, wire, ProtoWire.FIXED64);
                    current = reader.readDouble();
                }
                case 5 -> {
                    // 故障码字符串。
                    requireWireType(field, wire, ProtoWire.LENGTH_DELIMITED);
                    faultCode = reader.readString();
                }
                // 跳过兼容格式的未知扩展字段。
                default -> reader.skip(wire);
            }
        }
        // 设备号、状态、电压和电流都是摘要必填字段；故障码允许为空。
        if (sn == null || status == null || Double.isNaN(voltage) || Double.isNaN(current)) {
            throw new ProtocolException("Payload misses a required field");
        }
        // receivedAt 使用网关当前毫秒时间，和设备的 timestamp 分开记录。
        return new ChargingReport(sn, status, voltage, current, faultCode,
                timestamp, sequence, Instant.now().toEpochMilli());
    }

    private static void requireWireType(int field, int actual, int expected) {
        // 错误 wire type 会导致长度和游标全部错位，因此不能尝试容错读取。
        if (actual != expected) {
            throw new ProtocolException("Invalid wire type for field " + field
                    + ": expected " + expected + " but was " + actual);
        }
    }

    private String signature(long timestamp) {
        try {
            // 获取 JDK 内置 MD5 实现，不需要外部依赖。
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            // 按工作任务要求拼接“密钥_时间戳”，并用 ASCII 转成稳定字节。
            byte[] digest = md5.digest((secret + "_" + timestamp).getBytes(StandardCharsets.US_ASCII));
            // 把 16 字节摘要转换成 32 位小写十六进制字符串。
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // 标准 JDK 必须提供 MD5；如果缺失，属于运行环境故障而非设备报文错误。
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
