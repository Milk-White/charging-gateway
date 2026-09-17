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
    private GatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        // 读取 JVM 系统属性；未传入时使用适合本地演示的默认值。
        int port = Integer.parseInt(System.getProperty("gateway.port", "8080"));
        Path dataDirectory = Path.of(System.getProperty("gateway.data", "data"));
        String secret = System.getProperty("gateway.secret", "demo-secret");
        Map<String, String> credentials = parseCredentials(System.getProperty(
                "gateway.credentials", "pile001:pwd001,pile002:pwd002,pile003:pwd003"));
        Set<String> registeredDevices = credentials.keySet();

        // 按分层依赖顺序创建组件：底层组件先创建，上层组件通过构造方法接收依赖。
        ChargingProtocolCodec codec = new ChargingProtocolCodec(secret);
        ChargingValueCodec valueCodec = new ChargingValueCodec();
        FileReportRepository repository = new FileReportRepository(dataDirectory);
        ReportService service = new ReportService(codec, repository, registeredDevices);
        DeviceSessionService sessions = new DeviceSessionService(credentials);
        DeviceProtocolService protocolService = new DeviceProtocolService(codec, valueCodec, sessions,
                service, new FileRealtimePushRepository(dataDirectory));
        GatewayHttpServer server = new GatewayHttpServer(port, service, codec, protocolService);

        // 开始监听端口；Java 进程会持续运行，直到在 IDEA 中停止或按 Ctrl+C。
        server.start();
        if (Boolean.parseBoolean(System.getProperty("gateway.mqtt.enabled", "false"))) {
            String mqttHost = System.getProperty("gateway.mqtt.host", "localhost");
            int mqttPort = Integer.parseInt(System.getProperty("gateway.mqtt.port", "1883"));
            String clientId = System.getProperty("gateway.mqtt.clientId", "charging-gateway");
            new Mqtt5GatewayAdapter(mqttHost, mqttPort, clientId, protocolService).start();
            System.out.println("MQTT 5.0 adapter enabled for " + mqttHost + ":" + mqttPort);
        }

        System.out.println("Charging gateway started at http://localhost:" + server.port());
        System.out.println("Persistence file: " + dataDirectory.toAbsolutePath().resolve("reports.tsv"));
        System.out.println("Full 103 file: " + dataDirectory.toAbsolutePath().resolve("realtime-pushes.tsv"));
        System.out.println("Registered devices: " + registeredDevices);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static Map<String, String> parseCredentials(String configured) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : configured.split(",")) {
            String[] parts = item.trim().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("gateway.credentials must use sn:password entries");
            }
            if (result.putIfAbsent(parts[0], parts[1]) != null) {
                throw new IllegalArgumentException("Duplicate device credential: " + parts[0]);
            }
        }
        return Map.copyOf(result);
    }
}
