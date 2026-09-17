package com.example.gateway;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ProtocolException;
import com.example.gateway.repository.FileReportRepository;
import com.example.gateway.service.ReportService;
import com.example.gateway.service.ValidationException;

import java.nio.file.Files;
import java.time.Instant;

/**
 * 不依赖第三方测试框架的项目自测入口。
 *
 * <p>用途：验证协议编解码、签名拒绝、文件持久化及字段校验四条关键链路。
 * 运行成功时输出 {@code PASS: 4 tests}。</p>
 */
public final class AllTests {
    private static int tests;

    public static void main(String[] args) throws Exception {
        codecRoundTrip();
        rejectsBrokenSignature();
        persistsAndQueriesReports();
        rejectsInvalidFields();
        System.out.println("PASS: " + tests + " tests");
    }

    private static void codecRoundTrip() {
        // 验证同一条数据经过“编码 -> 解码”后关键字段保持一致。
        ChargingProtocolCodec codec = new ChargingProtocolCodec("test-secret");
        ChargingReport source = sample("pile001", ChargingStatus.CHARGING, "");
        ChargingReport decoded = codec.decodeReport(codec.encodeReport(source));
        check(decoded.deviceSn().equals("pile001"), "SN round-trip");
        check(decoded.status() == ChargingStatus.CHARGING, "status round-trip");
        check(decoded.voltage() == 380.5, "voltage round-trip");
        tests++;
    }

    private static void rejectsBrokenSignature() {
        // 篡改协议帧最后一个字节，验证签名不匹配时必须拒绝报文。
        ChargingProtocolCodec codec = new ChargingProtocolCodec("test-secret");
        byte[] frame = codec.encodeReport(sample("pile001", ChargingStatus.IDLE, ""));
        frame[frame.length - 1] ^= 1;
        expect(ProtocolException.class, () -> codec.decodeReport(frame));
        tests++;
    }

    private static void persistsAndQueriesReports() throws Exception {
        // 验证保存、最新查询、历史查询以及重新创建仓库后的数据恢复。
        var directory = Files.createTempDirectory("charging-gateway-test-");
        ChargingProtocolCodec codec = new ChargingProtocolCodec("test-secret");
        FileReportRepository repository = new FileReportRepository(directory);
        ReportService service = new ReportService(codec, repository);
        service.acceptFrame(codec.encodeReport(sample("pile002", ChargingStatus.IDLE, "")));
        service.acceptFrame(codec.encodeReport(sample("pile002", ChargingStatus.FAULT, "3001")));
        check(service.latest("pile002").orElseThrow().status() == ChargingStatus.FAULT, "latest report");
        check(service.history("pile002", 10).size() == 2, "history size");
        FileReportRepository reloaded = new FileReportRepository(directory);
        check(reloaded.history("pile002", 10).size() == 2, "persistence reload");
        tests++;
    }

    private static void rejectsInvalidFields() throws Exception {
        // 验证非法设备号和缺失故障码都会在业务校验阶段被拒绝。
        var directory = Files.createTempDirectory("charging-gateway-validation-");
        ChargingProtocolCodec codec = new ChargingProtocolCodec("test-secret");
        ReportService service = new ReportService(codec, new FileReportRepository(directory));
        ChargingReport invalid = new ChargingReport("bad-sn!", ChargingStatus.CHARGING,
                380, 20, "", Instant.now().getEpochSecond(), 7, 0);
        expect(ValidationException.class, () -> service.acceptFrame(codec.encodeReport(invalid)));
        ChargingReport missingFault = sample("pile003", ChargingStatus.FAULT, "");
        expect(ValidationException.class, () -> service.acceptFrame(codec.encodeReport(missingFault)));
        tests++;
    }

    private static ChargingReport sample(String sn, ChargingStatus status, String fault) {
        return new ChargingReport(sn, status, 380.5, 32.25, fault,
                Instant.now().getEpochSecond(), 42, 0);
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }

    private static void expect(Class<? extends Throwable> expected, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + " but got " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " but no exception was thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
