package com.example.gateway.service;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.repository.ReportRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 设备上报的业务服务。
 *
 * <p>用途：统一编排“协议解码 -> 业务校验 -> 数据保存”，并提供最新状态和历史记录查询。
 * HTTP 接入层只负责收发请求，不在控制器中重复实现业务规则。</p>
 */
public final class ReportService {
    // 设备编号只允许 1-32 位 ASCII 字母或数字，避免特殊字符进入索引和存储文件。
    private static final Pattern SN = Pattern.compile("[0-9A-Za-z]{1,32}");
    // 故障码格式为四位十六进制字符，例如 3001、A02F。
    private static final Pattern DEFINED_FAULT_CODE = Pattern.compile("[1345][0-9A-Fa-f]{3}");
    // 允许设备时钟最多慢 5 分钟、快 1 分钟，用于降低旧报文和重放报文风险。
    private static final long MAX_PAST_SECONDS = 300;
    private static final long MAX_FUTURE_SECONDS = 60;

    private final ChargingProtocolCodec codec;
    private final ReportRepository repository;
    private final Set<String> registeredDeviceSns;

    public ReportService(ChargingProtocolCodec codec, ReportRepository repository) {
        this(codec, repository, Set.of("pile001", "pile002", "pile003"));
    }

    public ReportService(ChargingProtocolCodec codec, ReportRepository repository,
                         Set<String> registeredDeviceSns) {
        this.codec = codec;
        this.repository = repository;
        if (registeredDeviceSns == null || registeredDeviceSns.isEmpty()) {
            throw new IllegalArgumentException("At least one registered device SN is required");
        }
        for (String deviceSn : registeredDeviceSns) {
            if (deviceSn == null || !SN.matcher(deviceSn).matches()) {
                throw new IllegalArgumentException("Invalid registered device SN: " + deviceSn);
            }
        }
        this.registeredDeviceSns = Set.copyOf(registeredDeviceSns);
    }

    /**
     * 接收一帧正式设备报文。
     *
     * <p>用途：这是正式接口和模拟接口最终共用的核心入口。只有解码与所有业务校验都成功，
     * 才会调用存储层，因此非法数据不会写入 {@code reports.tsv}。</p>
     */
    public ChargingReport acceptFrame(byte[] frame) throws IOException {
        // 第一步：协议层解析字段，同时检查功能码、请求/响应标志和 MD5 签名。
        ChargingReport report = codec.decodeReport(frame);
        // 第二步：检查设备编号、数值范围、故障码和时间窗口等业务规则。
        validate(report);
        // 第三步：前两步成功后才落库，这是“非法数据不入库”的关键保证。
        repository.save(report);
        return report;
    }

    /** 查询设备最新状态；查询前同样校验设备编号格式。 */
    public Optional<ChargingReport> latest(String deviceSn) {
        validateSn(deviceSn);
        return repository.latest(deviceSn);
    }

    /** 查询设备历史记录，限制一次最多返回 1000 条，避免无界读取。 */
    public List<ChargingReport> history(String deviceSn, int limit) {
        validateSn(deviceSn);
        if (limit < 1 || limit > 1_000) {
            throw new ValidationException("limit must be between 1 and 1000");
        }
        return repository.history(deviceSn, limit);
    }

    /** 集中保存所有上报字段的业务规则，任何接入方式都必须经过这里。 */
    private void validate(ChargingReport report) {
        validateSn(report.deviceSn());
        validateRange("voltage", report.voltage(), 0, 1_000);
        validateRange("current", report.current(), 0, 1_000);

        String faultCode = report.faultCode();
        // 非空故障码必须满足格式要求；FAULT 状态还必须强制提供故障码。
        if (faultCode == null) {
            throw new ValidationException("faultCode must not be null");
        }
        if (!faultCode.isEmpty() && !DEFINED_FAULT_CODE.matcher(faultCode).matches()) {
            throw new ValidationException("faultCode must be empty or a defined 1xxx/3xxx/4xxx/5xxx code");
        }
        if (report.status() == ChargingStatus.FAULT && faultCode.isEmpty()) {
            throw new ValidationException("faultCode is required when status is FAULT");
        }

        // 校验设备报文时间，拒绝过旧或来自过远未来的数据。
        long now = Instant.now().getEpochSecond();
        if (report.protocolTimestamp() < now - MAX_PAST_SECONDS
                || report.protocolTimestamp() > now + MAX_FUTURE_SECONDS) {
            throw new ValidationException("protocol timestamp is outside the accepted clock-skew window");
        }
    }

    private void validateSn(String deviceSn) {
        if (deviceSn == null || !SN.matcher(deviceSn).matches()) {
            throw new ValidationException("deviceSn must contain 1-32 ASCII letters or digits");
        }
        if (!registeredDeviceSns.contains(deviceSn)) {
            throw new ValidationException("Unsupported deviceSn: " + deviceSn);
        }
    }

    private static void validateRange(String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max);
        }
    }
}
