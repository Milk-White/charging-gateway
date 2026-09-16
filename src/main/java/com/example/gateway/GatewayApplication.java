package com.example.gateway;

import com.example.gateway.http.GatewayHttpServer;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.repository.FileReportRepository;
import com.example.gateway.service.ReportService;

import java.nio.file.Path;

public final class GatewayApplication {
    private GatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("gateway.port", "8080"));
        Path dataDirectory = Path.of(System.getProperty("gateway.data", "data"));
        String secret = System.getProperty("gateway.secret", "demo-secret");

        ChargingProtocolCodec codec = new ChargingProtocolCodec(secret);
        FileReportRepository repository = new FileReportRepository(dataDirectory);
        ReportService service = new ReportService(codec, repository);
        GatewayHttpServer server = new GatewayHttpServer(port, service, codec);
        server.start();

        System.out.println("Charging gateway started at http://localhost:" + server.port());
        System.out.println("Persistence file: " + dataDirectory.toAbsolutePath().resolve("reports.tsv"));
        System.out.println("Press Ctrl+C to stop.");
    }
}
