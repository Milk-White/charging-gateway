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
    // 设备编号只允许 1-32 位 ASCII 字母或数字，避免特殊字符进入文件和查询路径。
    private static final Pattern SN = Pattern.compile("[0-9A-Za-z]{1,32}");
    // 故障码格式为四位十六进制字符，例如 3001、A02F。
    private static final Pattern DEFINED_FAULT_CODE = Pattern.compile("[1345][0-9A-Fa-f]{3}");
    // 允许设备时钟最多慢 5 分钟、快 1 分钟，用于降低旧报文和重放报文风险。
    private static final long MAX_PAST_SECONDS = 300;
    private static final long MAX_FUTURE_SECONDS = 60;

    // codec 把兼容接口收到的二进制帧转换为 ChargingReport。
    private final ChargingProtocolCodec codec;
    // repository 负责摘要数据的实际持久化和查询。
    private final ReportRepository repository;
    // registeredDeviceSns 是允许上报和查询的设备白名单。
    private final Set<String> registeredDeviceSns;

    public ReportService(ChargingProtocolCodec codec, ReportRepository repository) {
        // 便捷构造方法提供三个演示设备，再交给完整构造方法统一检查。
        this(codec, repository, Set.of("pile001", "pile002", "pile003"));
    }

    public ReportService(ChargingProtocolCodec codec, ReportRepository repository,
                         Set<String> registeredDeviceSns) {
        // 保存协议编解码器。
        this.codec = codec;
        // 保存仓储实现。
        this.repository = repository;
        // 空白名单意味着任何设备都无法使用，因此直接拒绝启动。
        if (registeredDeviceSns == null || registeredDeviceSns.isEmpty()) {
            throw new IllegalArgumentException("At least one registered device SN is required");
        }
        // 启动时逐个校验配置的设备号，尽早暴露错误配置。
        for (String deviceSn : registeredDeviceSns) {
            if (deviceSn == null || !SN.matcher(deviceSn).matches()) {
                throw new IllegalArgumentException("Invalid registered device SN: " + deviceSn);
            }
        }
        // 复制成只读 Set，防止运行过程中白名单被外部修改。
        this.registeredDeviceSns = Set.copyOf(registeredDeviceSns);
    }

    /**
     * 接收一帧正式设备报文。
     *
     * <p>用途：这是正式接口和模拟接口最终共用的核心入口。只有解码与所有业务校验都成功，
     * 才会调用存储层，因此非法数据不会写入 {@code reports.tsv}。</p>
     */
    public ChargingReport acceptFrame(byte[] frame) throws IOException {
        // 第一步：协议层解析摘要格式，同时检查功能码、方向、字段类型和 MD5。
        ChargingReport report = codec.decodeReport(frame);
        // 第二步：检查设备白名单、数值范围、故障码和时间窗口。
        validate(report);
        // 第三步：只有前两步成功后才落库，保证非法数据不进入 reports.tsv。
        repository.save(report);
        // 返回最终保存的对象，HTTP 层会把它序列化为 JSON。
        return report;
    }

    /** 接收已经由完整 103 点位协议转换出的摘要，仍执行相同业务校验和落库。 */
    public ChargingReport acceptDecoded(ChargingReport report) throws IOException {
        // 完整 103 转出的摘要也必须执行同一组业务规则。
        validate(report);
        // 校验成功后追加保存。
        repository.save(report);
        // 返回已保存摘要。
        return report;
    }

    /** 在多文件事务前预检摘要，保证非法点位不会写入任何持久化文件。 */
    public void validateDecoded(ChargingReport report) {
        // DeviceProtocolService 在写两个文件前先调用本方法做预检。
        validate(report);
    }

    /** 查询设备最新状态；查询前同样校验设备编号格式。 */
    public Optional<ChargingReport> latest(String deviceSn) {
        // 查询也只允许格式正确且已登记的设备号。
        validateSn(deviceSn);
        // 仓储用 Optional.empty 表示该设备尚无记录。
        return repository.latest(deviceSn);
    }

    /** 查询设备历史记录，限制一次最多返回 1000 条，避免无界读取。 */
    public List<ChargingReport> history(String deviceSn, int limit) {
        // 先校验设备，避免对任意字符串建立查询。
        validateSn(deviceSn);
        // 限制返回条数，防止一次查询复制过多内存数据。
        if (limit < 1 || limit > 1_000) {
            throw new ValidationException("limit must be between 1 and 1000");
        }
        // 仓储负责按最新在前返回最多 limit 条。
        return repository.history(deviceSn, limit);
    }

    /** 返回稳定排序的已登记设备清单，供监控页面和运维接口使用。 */
    public List<String> registeredDevices() {
        // Set 本身无稳定顺序，排序后网页每次显示顺序一致。
        return registeredDeviceSns.stream().sorted().toList();
    }

    /** 集中保存所有上报字段的业务规则，任何接入方式都必须经过这里。 */
    private void validate(ChargingReport report) {
        // 设备号既要满足格式，也必须出现在白名单。
        validateSn(report.deviceSn());
        // 电压必须是有限数字并位于 0 到 1000 之间。
        validateRange("voltage", report.voltage(), 0, 1_000);
        // 电流使用相同范围规则。
        validateRange("current", report.current(), 0, 1_000);

        // 单独取出故障码，后续三条规则都要使用。
        String faultCode = report.faultCode();
        // null 和空字符串含义不同；领域对象不允许 null。
        if (faultCode == null) {
            throw new ValidationException("faultCode must not be null");
        }
        // 非空故障码必须属于协议定义的 1xxx/3xxx/4xxx/5xxx 十六进制范围。
        if (!faultCode.isEmpty() && !DEFINED_FAULT_CODE.matcher(faultCode).matches()) {
            throw new ValidationException("faultCode must be empty or a defined 1xxx/3xxx/4xxx/5xxx code");
        }
        // 状态已经是 FAULT 时不能不给出具体故障码。
        if (report.status() == ChargingStatus.FAULT && faultCode.isEmpty()) {
            throw new ValidationException("faultCode is required when status is FAULT");
        }

        // 获取网关当前 UNIX 秒。
        long now = Instant.now().getEpochSecond();
        // 设备时间允许慢 5 分钟、快 1 分钟，超出则拒绝。
        if (report.protocolTimestamp() < now - MAX_PAST_SECONDS
                || report.protocolTimestamp() > now + MAX_FUTURE_SECONDS) {
            throw new ValidationException("protocol timestamp is outside the accepted clock-skew window");
        }
    }

    private void validateSn(String deviceSn) {
        // 先限制长度和字符范围，避免制表符等内容破坏 TSV 列结构。
        if (deviceSn == null || !SN.matcher(deviceSn).matches()) {
            throw new ValidationException("deviceSn must contain 1-32 ASCII letters or digits");
        }
        // 格式正确但没有登记的设备同样不能上报或查询。
        if (!registeredDeviceSns.contains(deviceSn)) {
            throw new ValidationException("Unsupported deviceSn: " + deviceSn);
        }
    }

    private static void validateRange(String field, double value, double min, double max) {
        // isFinite 同时排除 NaN、正无穷和负无穷，再检查闭区间边界。
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max);
        }
    }
}
