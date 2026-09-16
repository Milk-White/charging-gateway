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

public final class GatewayHttpServer {
    private final HttpServer server;
    private final ReportService service;
    private final ChargingProtocolCodec codec;
    private final AtomicInteger simulatorSequence = new AtomicInteger(1);

    public GatewayHttpServer(int port, ReportService service, ChargingProtocolCodec codec) throws IOException {
        this.service = service;
        this.codec = codec;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
            String body = readBody(exchange);
            String encoded = body.trim().startsWith("{") ? Json.string(body, "frameBase64") : body.trim();
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
            String body = readBody(exchange);
            String sn = Json.string(body, "deviceSn");
            ChargingStatus status = ChargingStatus.valueOf(Json.string(body, "status").toUpperCase());
            double voltage = Json.number(body, "voltage");
            double current = Json.number(body, "current");
            String faultCode = Json.string(body, "faultCode");
            long now = Instant.now().getEpochSecond();
            ChargingReport simulated = new ChargingReport(sn, status, voltage, current, faultCode,
                    now, simulatorSequence.getAndIncrement(), 0);
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
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length != 5) {
                send(exchange, 404, Json.error("NOT_FOUND", "Expected /api/devices/{sn}/latest or /history"));
                return;
            }
            String sn = parts[3];
            if ("latest".equals(parts[4])) {
                var latest = service.latest(sn);
                if (latest.isEmpty()) {
                    send(exchange, 404, Json.error("NOT_FOUND", "No report exists for device " + sn));
                } else {
                    send(exchange, 200, Json.report(latest.get()));
                }
                return;
            }
            if ("history".equals(parts[4])) {
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
        exchange.getResponseHeaders().set("Allow", expected);
        send(exchange, 405, Json.error("METHOD_NOT_ALLOWED", "Use " + expected));
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(70_000);
        if (bytes.length > ChargingProtocolCodec.MAX_FRAME_LENGTH + 1_000) {
            throw new IllegalArgumentException("Request body is too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
