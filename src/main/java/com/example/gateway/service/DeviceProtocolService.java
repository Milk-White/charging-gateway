package com.example.gateway.service;

import com.example.gateway.domain.ChargingPointAddresses;
import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.domain.LoginRequest;
import com.example.gateway.domain.PointValue;
import com.example.gateway.domain.RealtimePush;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ChargingValueCodec;
import com.example.gateway.protocol.ProtocolEnvelope;
import com.example.gateway.protocol.ProtocolException;
import com.example.gateway.repository.RealtimePushRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** 编排 101 登录、102 心跳和 103 完整实时数据处理，并返回正式协议响应帧。 */
public final class DeviceProtocolService {
    // 103 数据时间最多允许比网关当前时间早 5 分钟，防止过旧数据和重放。
    private static final long MAX_PAST_SECONDS = 300;
    // 设备时间允许最多快 1 分钟，用来容忍少量时钟误差。
    private static final long MAX_FUTURE_SECONDS = 60;
    // protocolCodec 处理 101/102/103 的公共外层帧。
    private final ChargingProtocolCodec protocolCodec;
    // valueCodec 专门处理 103 中的枪、点位和 oneof 值。
    private final ChargingValueCodec valueCodec;
    // sessions 负责验证密码、检查登录态和刷新最后活动时间。
    private final DeviceSessionService sessions;
    // reportService 负责校验并保存网页需要的摘要数据。
    private final ReportService reportService;
    // realtimeRepository 负责无损保存完整 103 业务载荷。
    private final RealtimePushRepository realtimeRepository;

    public DeviceProtocolService(ChargingProtocolCodec protocolCodec, ChargingValueCodec valueCodec,
                                 DeviceSessionService sessions, ReportService reportService,
                                 RealtimePushRepository realtimeRepository) {
        // 保存外层协议编解码器依赖。
        this.protocolCodec = protocolCodec;
        // 保存 103 点位编解码器依赖。
        this.valueCodec = valueCodec;
        // 保存登录会话服务依赖。
        this.sessions = sessions;
        // 保存摘要业务服务依赖。
        this.reportService = reportService;
        // 保存完整载荷仓储依赖。
        this.realtimeRepository = realtimeRepository;
    }

    public HandledMessage handle(String deviceSn, byte[] frame) throws IOException {
        // 第一步只解析并验证公共帧头，包括长度、字段类型和 MD5 签名。
        ProtocolEnvelope envelope = protocolCodec.decodeEnvelope(frame);
        // 网关的接入方向只接受设备请求，设备发来的响应帧属于方向错误。
        if (envelope.response()) {
            throw new ProtocolException("Gateway accepts request frames only");
        }
        try {
            // 根据功能码把同一入口分发到 101 登录、102 心跳或 103 实时数据流程。
            return switch (envelope.functionCode()) {
                // 101 需要额外解析用户名和密码。
                case ChargingProtocolCodec.LOGIN_FUNCTION -> login(deviceSn, frame, envelope);
                // 102 只需要验证并刷新会话。
                case ChargingProtocolCodec.HEARTBEAT_FUNCTION -> heartbeat(deviceSn, envelope);
                // 103 需要解析点位、业务校验并执行双重存储。
                case ChargingProtocolCodec.REALTIME_DATA_FUNCTION -> realtime(deviceSn, envelope);
                // 未实现的功能码不抛服务器异常，而是返回明确协议结果。
                default -> result(envelope, 1005, "UNSUPPORTED_FUNCTION", Optional.empty());
            };
        } catch (ProtocolException exception) {
            // wire 类型、字段或载荷结构错误统一映射为协议错误 2001。
            return result(envelope, 2001, "INVALID_PROTOCOL: " + exception.getMessage(), Optional.empty());
        } catch (ValidationException | IllegalArgumentException exception) {
            // 时间、设备状态和数值范围错误统一映射为数据错误 2002。
            return result(envelope, 2002, "INVALID_DATA: " + exception.getMessage(), Optional.empty());
        }
    }

    public DeviceSessionService sessions() {
        // HTTP 层通过这个方法读取活动会话，供监控大屏显示登录状态。
        return sessions;
    }

    private HandledMessage login(String deviceSn, byte[] frame, ProtocolEnvelope envelope) {
        // 从 101 的 Data 字段解析 UserName 和 Pwd。
        LoginRequest request = protocolCodec.decodeLoginRequest(frame);
        // 校验设备是否登记、用户名是否等于 SN、密码是否正确，并建立会话。
        DeviceSessionService.LoginResult login = sessions.login(deviceSn, request);
        // 无论成功或失败都返回与请求同序号、同功能码的正式 101 响应。
        return result(envelope, login.resultCode(), login.message(), Optional.empty());
    }

    private HandledMessage heartbeat(String deviceSn, ProtocolEnvelope envelope) {
        try {
            // touch 同时检查是否登录和是否超时，并更新最后活动时间。
            sessions.touch(deviceSn);
            // 登录有效时返回成功的 102 响应。
            return result(envelope, 0, "OK", Optional.empty());
        } catch (ValidationException exception) {
            // 从未登录或会话超时都返回 NOT_LOGGED_IN，设备应重新发送 101。
            return result(envelope, 1003, "NOT_LOGGED_IN", Optional.empty());
        }
    }

    private HandledMessage realtime(String deviceSn, ProtocolEnvelope envelope) throws IOException {
        try {
            // 103 的第一道业务门槛是必须已有有效 101 会话。
            sessions.requireLoggedIn(deviceSn);
        } catch (ValidationException exception) {
            // 未登录时立即返回，不解析、更不保存任何业务数据。
            return result(envelope, 1003, "NOT_LOGGED_IN", Optional.empty());
        }
        // 把外层 Data 字节解析成“数据类别 + 采集时间 + 枪列表 + 点位列表”。
        RealtimePush push = valueCodec.decode(envelope.data());
        // 检查业务采集时间，拒绝过旧或明显来自未来的报文。
        validateTime(push.recordTime());
        // 网关接收时间使用毫秒，和设备提供的秒级采集时间分开保存。
        long receivedAt = Instant.now().toEpochMilli();
        // 尝试从完整点位中提取状态、电压、电流和故障码，供网页快速查询。
        Optional<ChargingReport> summary = toSummary(deviceSn, envelope, push, receivedAt);
        // 摘要存在时先做全部校验，保证非法数据不会写入任何一个文件。
        if (summary.isPresent()) {
            reportService.validateDecoded(summary.get());
        }
        // 保存完整原始 103 数据，包括所有枪、所有点位和原始 Data 字节。
        realtimeRepository.save(deviceSn, envelope.packageSequence(), receivedAt, push, envelope.data());
        // 只有能形成常用摘要时才写 reports.tsv；特殊点位报文仍会保留在完整仓储。
        if (summary.isPresent()) {
            reportService.acceptDecoded(summary.get());
        }
        // 所有解析、校验和存储完成后才刷新会话活动时间。
        sessions.touch(deviceSn);
        // 返回成功响应，并把摘要附在内部结果中供 HTTP 兼容接口使用。
        return result(envelope, 0, "OK", summary);
    }

    private Optional<ChargingReport> toSummary(String deviceSn, ProtocolEnvelope envelope,
                                               RealtimePush push, long receivedAt) {
        // 在所有枪的点位中寻找设备状态点。
        PointValue statusPoint = find(push, ChargingPointAddresses.STATUS);
        // 寻找电压点。
        PointValue voltagePoint = find(push, ChargingPointAddresses.VOLTAGE);
        // 寻找电流点。
        PointValue currentPoint = find(push, ChargingPointAddresses.CURRENT);
        // 三个基础点缺少任意一个时无法形成网页摘要，但完整数据仍然可以保存。
        if (statusPoint == null || voltagePoint == null || currentPoint == null) {
            return Optional.empty();
        }
        // 状态既可以是字符串，也可以是整数枚举码，由 status 方法统一转换。
        ChargingStatus status = status(statusPoint);
        // 电压必须是 Number 子类，然后统一转为 double。
        double voltage = number(voltagePoint, "voltage");
        // 电流使用相同数值转换规则。
        double current = number(currentPoint, "current");
        // 故障码是可选点，正常状态可以不上传。
        PointValue faultPoint = find(push, ChargingPointAddresses.FAULT_CODE);
        // 没有故障码时保存空字符串，有值时转成字符串用于表格展示。
        String fault = faultPoint == null ? "" : String.valueOf(faultPoint.value());
        // 摘要同时保存设备时间、帧序号和网关接收时间，便于查询与追踪。
        return Optional.of(new ChargingReport(deviceSn, status, voltage, current, fault,
                envelope.timestamp(), envelope.packageSequence(), receivedAt));
    }

    private static PointValue find(RealtimePush push, int address) {
        // flatMap 把“多个枪各自的点位列表”合并成一条点位流。
        return push.guns().stream().flatMap(gun -> gun.points().stream())
                // 只保留地址等于目标地址的点位，并返回找到的第一个。
                .filter(point -> point.pointAddress() == address).findFirst().orElse(null);
    }

    private static ChargingStatus status(PointValue point) {
        try {
            // 字符串状态例如 "CHARGING"，转成大写后匹配枚举名称。
            if (point.value() instanceof String value) {
                return ChargingStatus.valueOf(value.toUpperCase());
            }
            // 整数状态例如 1，由 fromCode 映射到 ChargingStatus。
            if (point.value() instanceof Integer value) {
                return ChargingStatus.fromCode(value);
            }
        } catch (IllegalArgumentException exception) {
            // 枚举名称或数字代码不存在时转成统一业务校验异常。
            throw new ValidationException("Invalid status point: " + point.value());
        }
        // bool、float、double 等类型都不能表示设备状态。
        throw new ValidationException("Status point must be string or integer");
    }

    private static double number(PointValue point, String name) {
        // Integer、Float 和 Double 都实现 Number，可以统一读取 doubleValue。
        if (point.value() instanceof Number number) {
            return number.doubleValue();
        }
        // 字符串或布尔值出现在电压/电流点时属于数据类型错误。
        throw new ValidationException(name + " point must be numeric");
    }

    private static void validateTime(long timestamp) {
        // 获取网关当前 UNIX 秒级时间。
        long now = Instant.now().getEpochSecond();
        // 同时限制过去窗口和未来窗口，避免陈旧或错误时钟数据入库。
        if (timestamp < now - MAX_PAST_SECONDS || timestamp > now + MAX_FUTURE_SECONDS) {
            throw new ValidationException("Realtime record time is outside the accepted window");
        }
    }

    private HandledMessage result(ProtocolEnvelope request, int code, String message,
                                  Optional<ChargingReport> report) {
        // 先编码设备真正收到的二进制响应帧，再附上便于 HTTP 层读取的结构化字段。
        return new HandledMessage(protocolCodec.encodeResponse(request, code, message),
                request.functionCode(), code, message, report);
    }

    // HandledMessage 是一次协议处理结果：包含响应字节、功能码、结果码、消息及可选摘要。
    public record HandledMessage(byte[] responseFrame, int functionCode, int resultCode,
                                 String message, Optional<ChargingReport> report) {
        public HandledMessage {
            // 构造时复制数组，防止调用方之后修改传入数组。
            responseFrame = responseFrame.clone();
        }

        @Override
        public byte[] responseFrame() {
            // 读取时再次复制数组，防止外部修改记录内部保存的响应帧。
            return responseFrame.clone();
        }
    }
}
