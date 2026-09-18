package com.example.gateway.http;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ProtocolException;
import com.example.gateway.service.ReportService;
import com.example.gateway.service.DeviceProtocolService;
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
    // Base64 会把 3 字节变成 4 字符，再预留 2048 字节给 JSON 包装和空白。
    static final int MAX_REQUEST_BODY_LENGTH = ((ChargingProtocolCodec.MAX_FRAME_LENGTH + 2) / 3) * 4 + 2_048;
    // JDK 自带 HttpServer，负责真正监听端口和分派路径。
    private final HttpServer server;
    // 兼容摘要接口和设备查询使用的业务服务。
    private final ReportService service;
    // 网页模拟上报需要用它把摘要重新编码成正式二进制帧。
    private final ChargingProtocolCodec codec;
    // 101/102/103 正式协议调试接口调用的统一协议服务。
    private final DeviceProtocolService protocolService;
    // 网页每点一次模拟上报就递增帧序号，AtomicInteger 保证并发安全。
    private final AtomicInteger simulatorSequence = new AtomicInteger(1);

    public GatewayHttpServer(int port, ReportService service, ChargingProtocolCodec codec,
                             DeviceProtocolService protocolService) throws IOException {
        // 保存业务服务依赖。
        this.service = service;
        // 保存协议编解码器依赖。
        this.codec = codec;
        // 保存完整协议服务依赖。
        this.protocolService = protocolService;
        // 0 表示由系统使用默认连接队列长度；port 可以传 0 让测试随机选空闲端口。
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // 每个请求使用一个 Java 21 虚拟线程，适合大量等待网络/文件 I/O 的连接。
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // 每个 createContext 把 URL 前缀绑定到对应处理方法。
        this.server.createContext("/health", this::health);
        this.server.createContext("/api/reports", this::acceptFrame);
        this.server.createContext("/api/simulator/report", this::simulateReport);
        this.server.createContext("/api/devices", this::queryDevice);
        this.server.createContext("/api/protocol", this::protocolFrame);
        this.server.createContext("/api/sessions", this::sessions);
        this.server.createContext("/", this::dashboard);
    }

    /** HTTP 调试通道：路径提供设备 SN，请求和响应正文均为 Base64 协议帧。 */
    private void protocolFrame(HttpExchange exchange) throws IOException {
        // 该接口只允许 POST；method 已负责在不匹配时返回 405。
        if (!method(exchange, "POST")) {
            return;
        }
        // /api/protocol/pile001 按斜杠拆分后应得到四段。
        String[] parts = exchange.getRequestURI().getPath().split("/");
        // 缺少设备 SN 时返回 404，避免把空字符串当设备号处理。
        if (parts.length != 4 || parts[3].isBlank()) {
            send(exchange, 404, Json.error("NOT_FOUND", "Expected /api/protocol/{sn}"));
            return;
        }
        try {
            // 请求正文是 Base64 文本；trim 去除 PowerShell 或换行带来的空白。
            String encoded = readBody(exchange).trim();
            // Base64 解码得到设备实际发送的二进制协议帧。
            byte[] request = Base64.getDecoder().decode(encoded);
            // 路径最后一段是设备 SN，和协议帧一起交给统一服务处理。
            var handled = protocolService.handle(parts[3], request);
            // 响应帧重新编码为 Base64 文本，方便 curl/PowerShell 调试。
            sendText(exchange, 200, Base64.getEncoder().encodeToString(handled.responseFrame()));
        } catch (IllegalArgumentException | ProtocolException | ValidationException exception) {
            // Base64、协议或业务校验错误统一返回 400，并给出可读错误消息。
            send(exchange, 400, Json.error("INVALID_PROTOCOL_FRAME", exception.getMessage()));
        }
    }

    private void sessions(HttpExchange exchange) throws IOException {
        // 会话查询是只读操作，只允许 GET。
        if (!method(exchange, "GET")) {
            return;
        }
        // 取得仍未超时的会话并序列化为 JSON。
        send(exchange, 200, Json.sessions(protocolService.sessions().activeSessions()));
    }

    public void start() {
        // 开始监听构造时绑定的地址，之后请求会进入上面注册的处理器。
        server.start();
    }

    public int port() {
        // 返回实际端口；测试传 0 时可以通过它获知系统选择的端口。
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        // 健康检查只允许 GET。
        if (!method(exchange, "GET")) {
            return;
        }
        // 能执行到这里说明 HTTP 线程正常，返回最简单的 UP JSON。
        send(exchange, 200, "{\"status\":\"UP\"}");
    }

    private void dashboard(HttpExchange exchange) throws IOException {
        // 网页资源只允许 GET。
        if (!method(exchange, "GET")) {
            return;
        }
        // 根路径和 /index.html 都指向同一份内置页面。
        String path = exchange.getRequestURI().getPath();
        // 项目没有其他静态资源路径，未知地址返回 404。
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(exchange, 404, Json.error("NOT_FOUND", "Unknown resource"));
            return;
        }
        // 从 classpath 读取 HTML 字符串并转换为 UTF-8 响应字节。
        byte[] body = DashboardPage.content().getBytes(StandardCharsets.UTF_8);
        // 明确 HTML 类型和 UTF-8，浏览器才能正确显示中文。
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        // 禁止缓存，重启服务后刷新页面一定能拿到最新版。
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // 禁止浏览器猜测内容类型，降低脚本注入风险。
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // 页面只允许同源资源和内联样式/脚本，不连接第三方站点。
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
                        "script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:");
        // 先发送 200 状态和精确正文长度。
        exchange.sendResponseHeaders(200, body.length);
        // try-with-resources 确保响应流无论成功还是异常都会关闭。
        try (var output = exchange.getResponseBody()) {
            // 把整份页面字节写给浏览器。
            output.write(body);
        }
    }

    private void acceptFrame(HttpExchange exchange) throws IOException {
        // 兼容二进制摘要上报接口只允许 POST。
        if (!method(exchange, "POST")) {
            return;
        }
        try {
            // 一次性读取受大小限制的 UTF-8 请求正文。
            String body = readBody(exchange);
            // 既支持纯 Base64，也支持 {"frameBase64":"..."} JSON 包装。
            String encoded = body.trim().startsWith("{")
                    ? Json.string(Json.object(body), "frameBase64")
                    : body.trim();
            // Base64 解码后交给业务层完成协议解码、校验和保存。
            ChargingReport report = service.acceptFrame(Base64.getDecoder().decode(encoded));
            // 创建成功返回 201 和已落库记录 JSON。
            send(exchange, 201, Json.report(report));
        } catch (IllegalArgumentException | ProtocolException | ValidationException e) {
            // 文本、Base64、协议或业务错误都属于客户端输入错误。
            send(exchange, 400, Json.error("INVALID_REPORT", e.getMessage()));
        }
    }

    private void simulateReport(HttpExchange exchange) throws IOException {
        // 网页模拟上报会创建数据，因此只允许 POST。
        if (!method(exchange, "POST")) {
            return;
        }
        try {
            // 读取 JSON 文本并解析成扁平对象。
            String body = readBody(exchange);
            var json = Json.object(body);
            // 逐项读取并转换网页表单字段。
            String sn = Json.string(json, "deviceSn");
            ChargingStatus status = ChargingStatus.valueOf(Json.string(json, "status").toUpperCase());
            double voltage = Json.number(json, "voltage");
            double current = Json.number(json, "current");
            String faultCode = Json.string(json, "faultCode");
            // 模拟设备协议时间使用当前 UNIX 秒。
            long now = Instant.now().getEpochSecond();
            // getAndIncrement 返回当前序号，并为下次请求自动加 1。
            ChargingReport simulated = new ChargingReport(sn, status, voltage, current, faultCode,
                    now, simulatorSequence.getAndIncrement(), 0);
            // 模拟数据也先编码成二进制协议帧，避免网页绕过协议与业务校验。
            ChargingReport accepted = service.acceptFrame(codec.encodeReport(simulated));
            // 返回真正经过业务层并写入文件后的记录。
            send(exchange, 201, Json.report(accepted));
        } catch (IllegalArgumentException | ProtocolException | ValidationException e) {
            // 表单值、枚举、协议或业务校验失败时返回 400。
            send(exchange, 400, Json.error("INVALID_REPORT", e.getMessage()));
        }
    }

    private void queryDevice(HttpExchange exchange) throws IOException {
        // 所有设备查询接口都是只读 GET。
        if (!method(exchange, "GET")) {
            return;
        }
        try {
            // 精确根路径 /api/devices 返回平台注册设备清单。
            if ("/api/devices".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 200, Json.strings(service.registeredDevices()));
                return;
            }
            // 其他设备查询按斜杠拆解路径。
            String[] parts = exchange.getRequestURI().getPath().split("/");
            // 标准路径必须正好是 /api/devices/{sn}/{operation} 五段。
            if (parts.length != 5) {
                send(exchange, 404, Json.error("NOT_FOUND", "Expected /api/devices/{sn}/latest or /history"));
                return;
            }
            // 第四段就是要查询的设备 SN。
            String sn = parts[3];
            // latest 分支查询该设备最新一条摘要记录。
            if ("latest".equals(parts[4])) {
                var latest = service.latest(sn);
                // 没有任何记录时返回 404，而不是伪造空数据。
                if (latest.isEmpty()) {
                    send(exchange, 404, Json.error("NOT_FOUND", "No report exists for device " + sn));
                } else {
                    // 有记录时取 Optional 内的对象并序列化。
                    send(exchange, 200, Json.report(latest.get()));
                }
                return;
            }
            // history 分支查询倒序历史列表。
            if ("history".equals(parts[4])) {
                // 从 ?limit= 读取条数，没有参数时默认 100。
                int limit = queryLimit(exchange.getRequestURI());
                // 业务层还会校验设备号和 limit 范围。
                List<ChargingReport> reports = service.history(sn, limit);
                // 把列表序列化为 JSON 数组。
                send(exchange, 200, Json.reports(reports));
                return;
            }
            // 最后一段既不是 latest 也不是 history。
            send(exchange, 404, Json.error("NOT_FOUND", "Unknown device query"));
        } catch (IllegalArgumentException | ValidationException e) {
            // limit 非数字、越界或设备未登记时返回 400。
            send(exchange, 400, Json.error("INVALID_QUERY", e.getMessage()));
        }
    }

    private static int queryLimit(URI uri) {
        // rawQuery 是问号后的原始文本，不包含开头的 ?。
        String query = uri.getRawQuery();
        // 没有查询参数时默认最多返回 100 条。
        if (query == null || query.isBlank()) {
            return 100;
        }
        // 支持多个用 & 连接的查询参数。
        for (String pair : query.split("&")) {
            // 每个参数最多按第一个等号拆成名称和值。
            String[] item = pair.split("=", 2);
            // 找到 limit 后转成整数；非法数字会由上层捕获并返回 400。
            if (item.length == 2 && "limit".equals(item[0])) {
                return Integer.parseInt(item[1]);
            }
        }
        // 参数中没有 limit 时仍使用默认 100。
        return 100;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        // HTTP 方法名称不区分大小写。
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        // Allow 响应头告诉客户端该路由允许使用什么方法。
        exchange.getResponseHeaders().set("Allow", expected);
        // 发送 405 后返回 false，调用方法应立即结束。
        send(exchange, 405, Json.error("METHOD_NOT_ALLOWED", "Use " + expected));
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        // 多读 1 字节，用来判断正文是否超过允许上限。
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_LENGTH + 1);
        // 实际读到上限加 1 就说明客户端发送过大正文。
        if (bytes.length > MAX_REQUEST_BODY_LENGTH) {
            throw new IllegalArgumentException("Request body is too large");
        }
        // HTTP JSON 和 Base64 文本统一按 UTF-8 解码。
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        // 先把 JSON 字符串转成 UTF-8 字节，body.length 才是正确 Content-Length。
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        // 声明 JSON 类型和字符集。
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // 实时状态不应被浏览器或代理缓存。
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // 发送状态码和正文长度。
        exchange.sendResponseHeaders(status, body.length);
        // 自动关闭响应流。
        try (var output = exchange.getResponseBody()) {
            // 写出完整 JSON 正文。
            output.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String value) throws IOException {
        // Base64 只包含 ASCII 字符，使用 US_ASCII 可明确表达这一点。
        byte[] body = value.getBytes(StandardCharsets.US_ASCII);
        // 协议调试接口响应是纯文本而不是 JSON。
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        // 响应帧也禁止缓存。
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // 发送状态码和正文长度。
        exchange.sendResponseHeaders(status, body.length);
        // 写出 Base64 响应文本并自动关闭流。
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
