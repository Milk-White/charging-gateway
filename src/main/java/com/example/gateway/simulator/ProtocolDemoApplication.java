package com.example.gateway.simulator;

import com.example.gateway.domain.ChargingPointAddresses;
import com.example.gateway.domain.GunSnapshot;
import com.example.gateway.domain.LoginRequest;
import com.example.gateway.domain.PointValue;
import com.example.gateway.domain.RealtimePush;
import com.example.gateway.domain.ReportKind;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ChargingValueCodec;
import com.example.gateway.protocol.ProtocolEnvelope;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 一次性演示 101 登录、102 心跳、五类 103 推送和未登录拦截。 */
public final class ProtocolDemoApplication {
    private final ChargingProtocolCodec protocol = new ChargingProtocolCodec(
            System.getProperty("gateway.secret", "demo-secret"));
    private final ChargingValueCodec values = new ChargingValueCodec();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final String baseUrl = System.getProperty("demo.baseUrl", "http://localhost:8080");

    public static void main(String[] args) throws Exception {
        new ProtocolDemoApplication().run();
    }

    private void run() throws Exception {
        long now = now();
        ProtocolEnvelope rejected = send("pile002", protocol.encodeRealtimeRequest(
                sequence.getAndIncrement(), now, sample(ReportKind.REALTIME), values), 103);
        require(rejected.resultCode() == 1003, "未登录 103 应被拒绝");
        System.out.println("[1/8] 未登录 103 已拦截：" + rejected.resultMessage());

        ProtocolEnvelope login = send("pile001", protocol.encodeLoginRequest(
                sequence.getAndIncrement(), now(), new LoginRequest("pile001", "pwd001")), 101);
        require(login.resultCode() == 0, "101 登录失败");
        System.out.println("[2/8] 101 登录成功，响应时间可用于校时：" + login.timestamp());

        ProtocolEnvelope heartbeat = send("pile001", protocol.encodeHeartbeatRequest(
                sequence.getAndIncrement(), now()), 102);
        require(heartbeat.resultCode() == 0, "102 心跳失败");
        System.out.println("[3/8] 102 心跳成功");

        int step = 4;
        for (ReportKind kind : ReportKind.values()) {
            ProtocolEnvelope response = send("pile001", protocol.encodeRealtimeRequest(
                    sequence.getAndIncrement(), now(), sample(kind), values), 103);
            require(response.resultCode() == 0, kind + " 103 推送失败");
            System.out.println("[" + step++ + "/8] 103 " + kind + " 推送成功");
        }
        System.out.println("完整协议演示完成：101、102、五类 103 和未登录拦截均通过。");
    }

    private ProtocolEnvelope send(String sn, byte[] frame, int expectedFunction) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                baseUrl + "/api/protocol/" + sn).toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        byte[] body = Base64.getEncoder().encode(frame);
        connection.setFixedLengthStreamingMode(body.length);
        try (var output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        byte[] responseBody = (status >= 400 ? connection.getErrorStream() : connection.getInputStream()).readAllBytes();
        if (status != 200) {
            throw new IllegalStateException("HTTP " + status + ": " + new String(responseBody));
        }
        return protocol.decodeResponse(Base64.getDecoder().decode(responseBody), expectedFunction);
    }

    private RealtimePush sample(ReportKind kind) {
        var points = new java.util.ArrayList<PointValue>();
        points.add(PointValue.text(ChargingPointAddresses.STATUS, "CHARGING"));
        points.add(PointValue.decimal(ChargingPointAddresses.VOLTAGE, 380.5));
        points.add(PointValue.decimal(ChargingPointAddresses.CURRENT, 32.25));
        points.add(PointValue.text(ChargingPointAddresses.FAULT_CODE, ""));
        points.add(PointValue.decimal(ChargingPointAddresses.CHARGER_TEMPERATURE, 36.5));
        if (kind == ReportKind.PUBLIC) {
            points.add(PointValue.text(ChargingPointAddresses.SOFTWARE_VERSION, "1.3"));
            points.add(PointValue.text(ChargingPointAddresses.QR_CODE, "PILE001-GUN1"));
        }
        if (kind == ReportKind.IDLE || kind == ReportKind.SPECIAL) {
            points.add(PointValue.decimal(ChargingPointAddresses.PHASE_A_VOLTAGE, 220.1));
            points.add(PointValue.decimal(ChargingPointAddresses.METER_ENERGY, 1288.6));
        }
        if (kind == ReportKind.CHARGING) {
            points.add(PointValue.integer(ChargingPointAddresses.REMAINING_SECONDS, 1800));
            points.add(PointValue.decimal(ChargingPointAddresses.TOTAL_ENERGY, 8.6));
            points.add(PointValue.decimal(ChargingPointAddresses.POWER, 12.2));
            points.add(PointValue.text(ChargingPointAddresses.BMS_DATA, "SOC=68%"));
        }
        return new RealtimePush(kind, now(), List.of(new GunSnapshot(1, points)));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
