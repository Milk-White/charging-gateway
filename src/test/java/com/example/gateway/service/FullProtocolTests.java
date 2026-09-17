package com.example.gateway.service;

import com.example.gateway.domain.ChargingPointAddresses;
import com.example.gateway.domain.GunSnapshot;
import com.example.gateway.domain.LoginRequest;
import com.example.gateway.domain.PointValue;
import com.example.gateway.domain.RealtimePush;
import com.example.gateway.domain.ReportKind;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ChargingValueCodec;
import com.example.gateway.repository.FileRealtimePushRepository;
import com.example.gateway.repository.FileReportRepository;
import com.example.gateway.simulator.PushSchedulePolicy;
import com.example.gateway.transport.mqtt.Mqtt5GatewayAdapter;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 101/102/完整 103、会话、调度策略和 MQTT 主题的端到端自测。 */
public final class FullProtocolTests {
    private FullProtocolTests() {
    }

    public static int run() throws Exception {
        loginSucceedsAndReturnsProtocolResponse();
        loginRejectsWrongPassword();
        realtimeRequiresLogin();
        realtimePersistsFullAndSummaryData();
        heartbeatRefreshesSession();
        sessionExpiresAfterFiveMinutes();
        valueCodecPreservesAllOneofTypes();
        scheduleMatchesProtocolIntervals();
        mqttTopicExtractsDeviceSn();
        return 9;
    }

    private static void loginSucceedsAndReturnsProtocolResponse() throws Exception {
        Fixture fixture = fixture(new DeviceSessionService(Map.of("pile001", "pwd001")));
        byte[] request = fixture.codec.encodeLoginRequest(7, now(), new LoginRequest("pile001", "pwd001"));
        var handled = fixture.protocol.handle("pile001", request);
        var response = fixture.codec.decodeResponse(handled.responseFrame(), ChargingProtocolCodec.LOGIN_FUNCTION);
        check(response.packageSequence() == 7 && response.resultCode() == 0, "login response");
        check(fixture.sessions.isLoggedIn("pile001"), "login session");
    }

    private static void loginRejectsWrongPassword() throws Exception {
        Fixture fixture = fixture(new DeviceSessionService(Map.of("pile001", "pwd001")));
        byte[] request = fixture.codec.encodeLoginRequest(8, now(), new LoginRequest("pile001", "bad"));
        var handled = fixture.protocol.handle("pile001", request);
        check(handled.resultCode() == 1002 && !fixture.sessions.isLoggedIn("pile001"), "bad password");
    }

    private static void realtimeRequiresLogin() throws Exception {
        Fixture fixture = fixture(new DeviceSessionService(Map.of("pile001", "pwd001")));
        byte[] request = fixture.codec.encodeRealtimeRequest(9, now(), samplePush(), fixture.valueCodec);
        check(fixture.protocol.handle("pile001", request).resultCode() == 1003, "login required");
    }

    private static void realtimePersistsFullAndSummaryData() throws Exception {
        Fixture fixture = fixture(new DeviceSessionService(Map.of("pile001", "pwd001")));
        login(fixture);
        byte[] request = fixture.codec.encodeRealtimeRequest(10, now(), samplePush(), fixture.valueCodec);
        var handled = fixture.protocol.handle("pile001", request);
        check(handled.resultCode() == 0 && handled.report().isPresent(), "103 accepted");
        check(fixture.reportService.latest("pile001").orElseThrow().voltage() == 380.5, "summary saved");
        check(Files.size(fixture.directory.resolve("realtime-pushes.tsv")) > 0, "full payload saved");
    }

    private static void heartbeatRefreshesSession() throws Exception {
        AtomicLong time = new AtomicLong(1_000);
        DeviceSessionService sessions = new DeviceSessionService(Map.of("pile001", "pwd001"),
                time::get, 300_000);
        Fixture fixture = fixture(sessions);
        login(fixture);
        time.addAndGet(120_000);
        byte[] heartbeat = fixture.codec.encodeHeartbeatRequest(11, now());
        check(fixture.protocol.handle("pile001", heartbeat).resultCode() == 0, "heartbeat accepted");
        check(sessions.activeSessions().getFirst().lastSeenAt() == time.get(), "heartbeat touched session");
    }

    private static void sessionExpiresAfterFiveMinutes() {
        AtomicLong time = new AtomicLong(1_000);
        DeviceSessionService sessions = new DeviceSessionService(Map.of("pile001", "pwd001"),
                time::get, 300_000);
        check(sessions.login("pile001", new LoginRequest("pile001", "pwd001")).success(), "login");
        time.addAndGet(300_000);
        check(!sessions.isLoggedIn("pile001"), "expired session");
    }

    private static void valueCodecPreservesAllOneofTypes() {
        ChargingValueCodec codec = new ChargingValueCodec();
        RealtimePush source = new RealtimePush(ReportKind.PUBLIC, now(), List.of(new GunSnapshot(0, List.of(
                PointValue.bool(1, true), PointValue.integer(2, 7), PointValue.floating(3, 1.5f),
                PointValue.decimal(4, 2.5), PointValue.text(5, "ok")))));
        RealtimePush decoded = codec.decode(codec.encode(source));
        check(decoded.guns().getFirst().points().equals(source.guns().getFirst().points()), "oneof values");
    }

    private static void scheduleMatchesProtocolIntervals() {
        PushSchedulePolicy policy = new PushSchedulePolicy();
        check(policy.periodicInterval(ReportKind.REALTIME).equals(Duration.ofSeconds(5)), "realtime interval");
        check(policy.periodicInterval(ReportKind.IDLE).equals(Duration.ofMinutes(5)), "idle interval");
        check(policy.periodicInterval(ReportKind.CHARGING).equals(Duration.ofMinutes(15)), "charging interval");
        check(policy.changeInterval(ReportKind.CHARGING).equals(Duration.ofSeconds(30)), "change interval");
        check(policy.retryDelay(ReportKind.CHARGING, true).equals(Duration.ofSeconds(15)), "retry interval");
    }

    private static void mqttTopicExtractsDeviceSn() {
        check(Mqtt5GatewayAdapter.extractDeviceSn(
                "charging/tocloud/pile001/protobuf/general").equals("pile001"), "mqtt topic");
    }

    private static void login(Fixture fixture) throws Exception {
        byte[] login = fixture.codec.encodeLoginRequest(1, now(), new LoginRequest("pile001", "pwd001"));
        check(fixture.protocol.handle("pile001", login).resultCode() == 0, "fixture login");
    }

    private static Fixture fixture(DeviceSessionService sessions) throws Exception {
        var directory = Files.createTempDirectory("charging-full-protocol-");
        ChargingProtocolCodec protocolCodec = new ChargingProtocolCodec("test-secret");
        ChargingValueCodec valueCodec = new ChargingValueCodec();
        ReportService reportService = new ReportService(protocolCodec,
                new FileReportRepository(directory), Map.of("pile001", "pwd001").keySet());
        DeviceProtocolService protocol = new DeviceProtocolService(protocolCodec, valueCodec, sessions,
                reportService, new FileRealtimePushRepository(directory));
        return new Fixture(directory, protocolCodec, valueCodec, sessions, reportService, protocol);
    }

    private static RealtimePush samplePush() {
        return new RealtimePush(ReportKind.CHARGING, now(), List.of(new GunSnapshot(1, List.of(
                PointValue.text(ChargingPointAddresses.STATUS, "CHARGING"),
                PointValue.decimal(ChargingPointAddresses.VOLTAGE, 380.5),
                PointValue.decimal(ChargingPointAddresses.CURRENT, 32.25),
                PointValue.text(ChargingPointAddresses.FAULT_CODE, ""),
                PointValue.decimal(ChargingPointAddresses.POWER, 12.2),
                PointValue.text(ChargingPointAddresses.BMS_DATA, "SOC=68%")))));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }

    private record Fixture(java.nio.file.Path directory, ChargingProtocolCodec codec,
                           ChargingValueCodec valueCodec, DeviceSessionService sessions,
                           ReportService reportService, DeviceProtocolService protocol) {
    }
}
