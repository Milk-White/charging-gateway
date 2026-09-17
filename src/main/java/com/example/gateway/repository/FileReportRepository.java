package com.example.gateway.repository;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 TSV 文件的上报存储实现。
 *
 * <p>用途：文件提供进程重启后的持久化能力，内存索引提供按设备快速查询能力。
 * 该实现适合单机、小数据量演示；生产环境应替换为数据库或时序存储。</p>
 */
public final class FileReportRepository implements ReportRepository {
    // 持久化文件路径，默认是项目目录下的 data/reports.tsv。
    private final Path storageFile;
    // 按设备编号组织内存索引，避免每次查询都扫描整个文件。
    private final Map<String, List<ChargingReport>> reportsByDevice = new ConcurrentHashMap<>();

    public FileReportRepository(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        this.storageFile = dataDirectory.resolve("reports.tsv");
        // 启动时读取历史文件，恢复内存索引，因此重启后仍能查询旧记录。
        if (Files.exists(storageFile)) {
            loadExisting();
        }
    }

    @Override
    public synchronized void save(ChargingReport report) throws IOException {
        // 先以追加方式写文件；写入成功后再更新内存索引，避免展示未持久化的数据。
        try (BufferedWriter writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(toLine(report));
            writer.newLine();
        }
        reportsByDevice.computeIfAbsent(report.deviceSn(), ignored -> new ArrayList<>()).add(report);
    }

    @Override
    public synchronized Optional<ChargingReport> latest(String deviceSn) {
        List<ChargingReport> reports = reportsByDevice.get(deviceSn);
        if (reports == null || reports.isEmpty()) {
            return Optional.empty();
        }
        // 每个设备的记录按写入顺序保存，列表末尾即最新状态。
        return Optional.of(reports.get(reports.size() - 1));
    }

    @Override
    public synchronized List<ChargingReport> history(String deviceSn, int limit) {
        List<ChargingReport> reports = reportsByDevice.get(deviceSn);
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, reports.size() - limit);
        List<ChargingReport> result = new ArrayList<>(reports.subList(from, reports.size()));
        // 对外返回“最新记录在前”，同时返回不可修改副本保护内部索引。
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    private void loadExisting() throws IOException {
        int lineNumber = 0;
        for (String line : Files.readAllLines(storageFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                ChargingReport report = fromLine(line);
                reportsByDevice.computeIfAbsent(report.deviceSn(), ignored -> new ArrayList<>()).add(report);
            } catch (RuntimeException e) {
                // 持久化文件损坏时立即终止启动，避免静默忽略数据问题。
                throw new IOException("Corrupt persistence record at line " + lineNumber, e);
            }
        }
    }

    private static String toLine(ChargingReport report) {
        // 固定八列 TSV 格式，空故障码也保留最后一列。
        return String.join("\t",
                Long.toString(report.receivedAt()),
                Long.toString(report.protocolTimestamp()),
                Integer.toString(report.packageSequence()),
                report.deviceSn(),
                report.status().name(),
                Double.toString(report.voltage()),
                Double.toString(report.current()),
                report.faultCode());
    }

    private static ChargingReport fromLine(String line) {
        // 使用 -1 保留行尾空列，确保空 faultCode 仍能解析为第八列。
        String[] parts = line.split("\t", -1);
        if (parts.length != 8) {
            throw new IllegalArgumentException("Expected 8 columns");
        }
        return new ChargingReport(
                parts[3],
                ChargingStatus.valueOf(parts[4]),
                Double.parseDouble(parts[5]),
                Double.parseDouble(parts[6]),
                parts[7],
                Long.parseLong(parts[1]),
                Integer.parseInt(parts[2]),
                Long.parseLong(parts[0]));
    }
}
