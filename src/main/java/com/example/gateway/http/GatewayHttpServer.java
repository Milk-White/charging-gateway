package com.example.gateway.http;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ProtocolException;
import com.example.gateway.service.ReportService;
import com.example.gateway.service.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关的 HTTP 接入层。
 *
 * <p>用途：负责路由、读取请求、转换输入和输出 HTTP 状态码；协议解析、业务校验和持久化
 * 交给下层组件处理，使接入方式与核心业务解耦。</p>
 */
public final class GatewayHttpServer {
    private final HttpServer server;
    private final ReportService service;
    private final ChargingProtocolCodec codec;
    private final AtomicInteger simulatorSequence = new AtomicInteger(1);

    public GatewayHttpServer(int port, ReportService service, ChargingProtocolCodec codec) throws IOException {
        this.service = service;
        this.codec = codec;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // 每个请求使用一个 Java 21 虚拟线程，适合大量 I/O 型设备连接的演示。
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // 注册健康检查、正式二进制上报、模拟上报和设备查询四组路由。
        this.server.createContext("/health", this::health);
        this.server.createContext("/api/reports", this::acceptFrame);
        this.server.createContext("/api/simulator/report", this::simulateReport);
        this.server.createContext("/api/devices", this::queryDevice);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        send(exchange, 200, "{\"status\":\"UP\"}");
    }

    private void acceptFrame(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        try {
            // 正式入口接收 Base64 文本，也兼容 {"frameBase64":"..."} 包装格式。
            String body = readBody(exchange);
            String encoded = body.trim().startsWith("{") ? Json.string(body, "frameBase64") : body.trim();
            // 解码 Base64 后交给业务服务；服务内部继续完成协议解码、校验和保存。
            ChargingReport report = service.acceptFrame(Base64.getDecoder().decode(encoded));
            send(exchange, 201, Json.report(report));
        } catch (IllegalArgumentException | ProtocolException | ValidationException e) {
            send(exchange, 400, Json.error("INVALID_REPORT", e.getMessage()));
        }
    }

    private void simulateReport(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        try {
            // JSON 仅用于方便现场演示，先将字段转换为领域对象。
            String body = readBody(exchange);
            String sn = Json.string(body, "deviceSn");
            ChargingStatus status = ChargingStatus.valueOf(Json.string(body, "status").toUpperCase());
            double voltage = Json.number(body, "voltage");
            double current = Json.number(body, "current");
            String faultCode = Json.string(body, "faultCode");
            long now = Instant.now().getEpochSecond();
            ChargingReport simulated = new ChargingReport(sn, status, voltage, current, faultCode,
                    now, simulatorSequence.getAndIncrement(), 0);
            // 关键点：模拟数据先编码成正式二进制帧，再走与真实设备相同的 acceptFrame 流程。
            ChargingReport accepted = service.acceptFrame(codec.encodeReport(simulated));
            send(exchange, 201, Json.report(accepted));
        } catch (IllegalArgumentException | ProtocolException | ValidationException e) {
            send(exchange, 400, Json.error("INVALID_REPORT", e.getMessage()));
        }
    }

    private void queryDevice(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        try {
            // 预期路径：/api/devices/{sn}/latest 或 /api/devices/{sn}/history。
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length != 5) {
                send(exchange, 404, Json.error("NOT_FOUND", "Expected /api/devices/{sn}/latest or /history"));
                return;
            }
            String sn = parts[3];
            if ("latest".equals(parts[4])) {
                // latest 返回该设备最后一次成功入库的数据。
                var latest = service.latest(sn);
                if (latest.isEmpty()) {
                    send(exchange, 404, Json.error("NOT_FOUND", "No report exists for device " + sn));
                } else {
                    send(exchange, 200, Json.report(latest.get()));
                }
                return;
            }
            if ("history".equals(parts[4])) {
                // history 支持 limit 参数，结果按最新记录在前的顺序返回。
                int limit = queryLimit(exchange.getRequestURI());
                List<ChargingReport> reports = service.history(sn, limit);
                send(exchange, 200, Json.reports(reports));
                return;
            }
            send(exchange, 404, Json.error("NOT_FOUND", "Unknown device query"));
        } catch (IllegalArgumentException | ValidationException e) {
            send(exchange, 400, Json.error("INVALID_QUERY", e.getMessage()));
        }
    }

    private static int queryLimit(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return 100;
        }
        for (String pair : query.split("&")) {
            String[] item = pair.split("=", 2);
            if (item.length == 2 && "limit".equals(item[0])) {
                return Integer.parseInt(item[1]);
            }
        }
        return 100;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        // 请求方法不符合路由约定时返回 405，并通过 Allow 告知正确方法。
        exchange.getResponseHeaders().set("Allow", expected);
        send(exchange, 405, Json.error("METHOD_NOT_ALLOWED", "Use " + expected));
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        // 限制请求体大小，避免异常大报文占用过多内存。
        byte[] bytes = exchange.getRequestBody().readNBytes(70_000);
        if (bytes.length > ChargingProtocolCodec.MAX_FRAME_LENGTH + 1_000) {
            throw new IllegalArgumentException("Request body is too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        // 所有接口统一返回 UTF-8 JSON，并禁止缓存设备实时状态响应。
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
