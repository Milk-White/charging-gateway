package com.example.gateway;

import com.example.gateway.http.GatewayHttpServer;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ChargingValueCodec;
import com.example.gateway.repository.FileReportRepository;
import com.example.gateway.repository.FileRealtimePushRepository;
import com.example.gateway.service.DeviceProtocolService;
import com.example.gateway.service.DeviceSessionService;
import com.example.gateway.service.ReportService;
import com.example.gateway.transport.mqtt.Mqtt5GatewayAdapter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 程序启动入口。
 *
 * <p>用途：读取运行参数，按“协议层 -> 存储层 -> 业务层 -> 接入层”的顺序组装对象，
 * 最后启动 HTTP 服务。这里相当于整个项目的依赖装配中心。</p>
 */
public final class GatewayApplication {
    // 这是纯启动类，不允许 new GatewayApplication() 创建无意义的对象。
    private GatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        // 从 -Dgateway.port 读取 HTTP 端口；没有配置时使用 8080。
        int port = Integer.parseInt(System.getProperty("gateway.port", "8080"));
        // 数据目录存放 reports.tsv 和 realtime-pushes.tsv，默认是项目下的 data。
        Path dataDirectory = Path.of(System.getProperty("gateway.data", "data"));
        // secret 用来计算协议帧 MD5 签名；生产环境必须替换默认演示值。
        String secret = System.getProperty("gateway.secret", "demo-secret");
        // 把“设备编号:密码”的配置文本解析成 Map，供 101 登录校验使用。
        Map<String, String> credentials = parseCredentials(System.getProperty(
                "gateway.credentials", "pile001:pwd001,pile002:pwd002,pile003:pwd003"));
        // 凭据表的 key 就是平台注册设备清单；Map.copyOf 保证后续不能被修改。
        Set<String> registeredDevices = credentials.keySet();

        // 创建外层协议编解码器：负责帧头、功能码、时间戳和 MD5 签名。
        ChargingProtocolCodec codec = new ChargingProtocolCodec(secret);
        // 创建 103 业务数据编解码器：负责枪、点位及 oneof 值。
        ChargingValueCodec valueCodec = new ChargingValueCodec();
        // 创建摘要仓储：网页查询的状态、电压、电流等写入 reports.tsv。
        FileReportRepository repository = new FileReportRepository(dataDirectory);
        // 创建摘要业务服务：执行设备、时间、状态和数值范围校验。
        ReportService service = new ReportService(codec, repository, registeredDevices);
        // 创建会话服务：保存 101 登录状态，并在 5 分钟无消息后使会话失效。
        DeviceSessionService sessions = new DeviceSessionService(credentials);
        // 创建协议总入口：按照 101、102、103 分发，并协调会话、校验和双重存储。
        DeviceProtocolService protocolService = new DeviceProtocolService(codec, valueCodec, sessions,
                service, new FileRealtimePushRepository(dataDirectory));
        // 创建 HTTP 服务：提供协议调试接口、查询 API 和监控网页。
        GatewayHttpServer server = new GatewayHttpServer(port, service, codec, protocolService);

        // 真正绑定端口并开始接收 HTTP 请求；执行后 Java 进程会持续运行。
        server.start();
        // MQTT 默认关闭，只有明确传入 -Dgateway.mqtt.enabled=true 才连接 Broker。
        if (Boolean.parseBoolean(System.getProperty("gateway.mqtt.enabled", "false"))) {
            // 读取 Broker 主机名，未配置时连接本机。
            String mqttHost = System.getProperty("gateway.mqtt.host", "localhost");
            // 读取 Broker 端口，MQTT 非 TLS 默认端口是 1883。
            int mqttPort = Integer.parseInt(System.getProperty("gateway.mqtt.port", "1883"));
            // clientId 是网关连接到 Broker 时使用的客户端唯一标识。
            String clientId = System.getProperty("gateway.mqtt.clientId", "charging-gateway");
            // 创建适配器并在后台线程执行 CONNECT、SUBSCRIBE、PING 和重连。
            new Mqtt5GatewayAdapter(mqttHost, mqttPort, clientId, protocolService).start();
            // 输出启用信息，便于演示时确认 MQTT 配置已经生效。
            System.out.println("MQTT 5.0 adapter enabled for " + mqttHost + ":" + mqttPort);
        }

        // 以下五行只输出启动摘要，不参与业务处理。
        System.out.println("Charging gateway started at http://localhost:" + server.port());
        System.out.println("Persistence file: " + dataDirectory.toAbsolutePath().resolve("reports.tsv"));
        System.out.println("Full 103 file: " + dataDirectory.toAbsolutePath().resolve("realtime-pushes.tsv"));
        System.out.println("Registered devices: " + registeredDevices);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static Map<String, String> parseCredentials(String configured) {
        // LinkedHashMap 会保留配置中的设备顺序，控制台展示更稳定。
        Map<String, String> result = new LinkedHashMap<>();
        // 先按逗号切成 pile001:pwd001 这样的单个设备配置。
        for (String item : configured.split(",")) {
            // 最多切成两段，避免密码中出现冒号时被无限拆分。
            String[] parts = item.trim().split(":", 2);
            // 缺少设备号、密码或冒号时立即拒绝启动，防止产生不可登录的配置。
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("gateway.credentials must use sn:password entries");
            }
            // putIfAbsent 只在设备第一次出现时写入；返回非 null 说明设备号重复。
            if (result.putIfAbsent(parts[0], parts[1]) != null) {
                throw new IllegalArgumentException("Duplicate device credential: " + parts[0]);
            }
        }
        // 返回只读副本，避免启动后有人意外修改登录凭据。
        return Map.copyOf(result);
    }
}
