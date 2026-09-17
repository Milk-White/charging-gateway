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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可独立运行的充电桩模拟器：101 登录后按文档周期发送 102 心跳和五类 103 数据。
 */
public final class DeviceSimulatorApplication {
    private final String baseUrl;
    private final String deviceSn;
    private final String password;
    private final ChargingProtocolCodec protocolCodec;
    private final ChargingValueCodec valueCodec = new ChargingValueCodec();
    private final PushSchedulePolicy policy = new PushSchedulePolicy();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final AtomicInteger sequence = new AtomicInteger(1);
    private volatile boolean loggedIn;
    private volatile long clockOffsetSeconds;

    private DeviceSimulatorApplication(String baseUrl, String deviceSn, String password, String secret) {
        this.baseUrl = baseUrl;
        this.deviceSn = deviceSn;
        this.password = password;
        this.protocolCodec = new ChargingProtocolCodec(secret);
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getProperty("simulator.baseUrl", "http://localhost:8080");
        String deviceSn = System.getProperty("simulator.sn", "pile001");
        String password = System.getProperty("simulator.password", "pwd001");
        String secret = System.getProperty("gateway.secret", "demo-secret");
        new DeviceSimulatorApplication(baseUrl, deviceSn, password, secret).start();
    }

    private void start() throws Exception {
        while (!login()) {
            System.out.println("101 login failed; retrying in 30 seconds.");
            Thread.sleep(policy.LOGIN_RETRY_DELAY.toMillis());
        }
        System.out.println("101 login succeeded for " + deviceSn);
        sendWithRetry(ReportKind.PUBLIC, false);
        schedule(ReportKind.REALTIME, policy.periodicInterval(ReportKind.REALTIME), false);
        schedule(ReportKind.PUBLIC, policy.periodicInterval(ReportKind.PUBLIC), false);
        schedule(ReportKind.CHARGING, policy.periodicInterval(ReportKind.CHARGING), false);
        schedule(ReportKind.CHARGING, policy.changeInterval(ReportKind.CHARGING), true);
        scheduler.scheduleWithFixedDelay(this::heartbeatSafely, 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::specialFixedPointSafely, 1, 1, TimeUnit.SECONDS);
        System.out.println("Simulator started: 102 every 30s, realtime 5s, charging change 30s, periodic 15min.");
        Thread.currentThread().join();
    }

    private void schedule(ReportKind kind, Duration interval, boolean changePush) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ensureLogin();
                sendWithRetry(kind, changePush);
            } catch (Exception exception) {
                System.err.println(kind + " push failed: " + exception.getMessage());
            }
        }, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
    }

    private boolean login() throws Exception {
        long now = protocolTime();
        byte[] request = protocolCodec.encodeLoginRequest(sequence.getAndIncrement(), now,
                new LoginRequest(deviceSn, password));
        ProtocolEnvelope response = send(request, ChargingProtocolCodec.LOGIN_FUNCTION);
        loggedIn = response.resultCode() == 0;
        if (loggedIn) {
            clockOffsetSeconds = response.timestamp() - Instant.now().getEpochSecond();
            System.out.println("Clock calibrated from 101 response; offset=" + clockOffsetSeconds + "s");
        }
        return loggedIn;
    }

    private void ensureLogin() throws Exception {
        if (!loggedIn && !login()) {
            throw new IllegalStateException("Login rejected");
        }
    }

    private void heartbeatSafely() {
        try {
            ensureLogin();
            ProtocolEnvelope response = send(protocolCodec.encodeHeartbeatRequest(
                    sequence.getAndIncrement(), protocolTime()),
                    ChargingProtocolCodec.HEARTBEAT_FUNCTION);
            if (response.resultCode() != 0) {
                loggedIn = false;
            }
        } catch (Exception exception) {
            loggedIn = false;
            System.err.println("Heartbeat failed: " + exception.getMessage());
        }
    }

    private void specialFixedPointSafely() {
        try {
            Instant now = Instant.now();
            if (policy.isSpecialFixedPoint(now)) {
                ensureLogin();
                sendWithRetry(ReportKind.SPECIAL, false);
            }
        } catch (Exception exception) {
            System.err.println("Special push failed: " + exception.getMessage());
        }
    }

    private void sendWithRetry(ReportKind kind, boolean changePush) throws Exception {
        int retries = 0;
        while (true) {
            long now = protocolTime();
            RealtimePush push = sample(kind, now);
            byte[] request = protocolCodec.encodeRealtimeRequest(sequence.getAndIncrement(), now, push, valueCodec);
            try {
                ProtocolEnvelope response = send(request, ChargingProtocolCodec.REALTIME_DATA_FUNCTION);
                if (response.resultCode() == 0) {
                    System.out.println("103 " + kind + " accepted at " + Instant.now());
                    return;
                }
                if (response.resultCode() == 1003) {
                    loggedIn = false;
                    ensureLogin();
                }
                if (++retries > policy.maxRetries(kind, changePush)) {
                    throw new IllegalStateException("Protocol rejected 103: " + response.resultMessage());
                }
                Thread.sleep(policy.retryDelay(kind, changePush).toMillis());
            } catch (Exception exception) {
                if (++retries > policy.maxRetries(kind, changePush)) {
                    throw exception;
                }
                Thread.sleep(policy.retryDelay(kind, changePush).toMillis());
            }
        }
    }

    private long protocolTime() {
        return Instant.now().getEpochSecond() + clockOffsetSeconds;
    }

    private ProtocolEnvelope send(byte[] frame, int functionCode) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                baseUrl + "/api/protocol/" + deviceSn).toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout((int) policy.LOGIN_RESPONSE_TIMEOUT.toMillis());
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
        return protocolCodec.decodeResponse(Base64.getDecoder().decode(responseBody), functionCode);
    }

    private RealtimePush sample(ReportKind kind, long now) {
        List<PointValue> points = new ArrayList<>();
        points.add(PointValue.text(ChargingPointAddresses.STATUS, "CHARGING"));
        points.add(PointValue.decimal(ChargingPointAddresses.VOLTAGE, 380.5));
        points.add(PointValue.decimal(ChargingPointAddresses.CURRENT, 32.25));
        points.add(PointValue.text(ChargingPointAddresses.FAULT_CODE, ""));
        points.add(PointValue.decimal(ChargingPointAddresses.CHARGER_TEMPERATURE, 36.5));
        if (kind == ReportKind.PUBLIC) {
            points.add(PointValue.text(ChargingPointAddresses.SOFTWARE_VERSION, "1.3"));
            points.add(PointValue.text(ChargingPointAddresses.HARDWARE_VERSION, "demo-hw-1"));
            points.add(PointValue.text(ChargingPointAddresses.QR_CODE, "PILE001-GUN1"));
        }
        if (kind == ReportKind.IDLE || kind == ReportKind.SPECIAL) {
            points.add(PointValue.decimal(ChargingPointAddresses.PHASE_A_VOLTAGE, 220.1));
            points.add(PointValue.decimal(ChargingPointAddresses.PHASE_B_VOLTAGE, 219.8));
            points.add(PointValue.decimal(ChargingPointAddresses.PHASE_C_VOLTAGE, 220.3));
            points.add(PointValue.decimal(ChargingPointAddresses.METER_ENERGY, 1288.6));
        }
        if (kind == ReportKind.CHARGING) {
            points.add(PointValue.integer(ChargingPointAddresses.REMAINING_SECONDS, 1800));
            points.add(PointValue.integer(ChargingPointAddresses.CHARGED_SECONDS, 900));
            points.add(PointValue.decimal(ChargingPointAddresses.TOTAL_ENERGY, 8.6));
            points.add(PointValue.decimal(ChargingPointAddresses.POWER, 12.2));
            points.add(PointValue.decimal(ChargingPointAddresses.GUN_TEMPERATURE, 38.2));
            points.add(PointValue.text(ChargingPointAddresses.BMS_DATA, "SOC=68%;V=380.5;I=32.25"));
        }
        return new RealtimePush(kind, now, List.of(new GunSnapshot(1, points)));
    }
}
