package com.example.gateway;

import com.example.gateway.http.GatewayHttpServer;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.repository.FileReportRepository;
import com.example.gateway.service.ReportService;

import java.nio.file.Path;

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

        // 按分层依赖顺序创建组件：底层组件先创建，上层组件通过构造方法接收依赖。
        ChargingProtocolCodec codec = new ChargingProtocolCodec(secret);
        FileReportRepository repository = new FileReportRepository(dataDirectory);
        ReportService service = new ReportService(codec, repository);
        GatewayHttpServer server = new GatewayHttpServer(port, service, codec);

        // 开始监听端口；Java 进程会持续运行，直到在 IDEA 中停止或按 Ctrl+C。
        server.start();

        System.out.println("Charging gateway started at http://localhost:" + server.port());
        System.out.println("Persistence file: " + dataDirectory.toAbsolutePath().resolve("reports.tsv"));
        System.out.println("Press Ctrl+C to stop.");
    }
}
