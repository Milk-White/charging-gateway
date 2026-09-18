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
    // storageFile 指向网页摘要持久化文件，默认是 data/reports.tsv。
    private final Path storageFile;
    // 按设备编号组织内存索引，避免每次查询都扫描整个文件。
    private final Map<String, List<ChargingReport>> reportsByDevice = new ConcurrentHashMap<>();

    public FileReportRepository(Path dataDirectory) throws IOException {
        // 目录不存在时递归创建；已存在时不会报错。
        Files.createDirectories(dataDirectory);
        // 在数据目录下固定使用 reports.tsv 文件名。
        this.storageFile = dataDirectory.resolve("reports.tsv");
        // 文件存在说明以前运行过，需要恢复历史索引。
        if (Files.exists(storageFile)) {
            loadExisting();
        }
    }

    @Override
    public synchronized void save(ChargingReport report) throws IOException {
        // synchronized 保证同一进程内两次保存不会交叉写入同一行。
        try (BufferedWriter writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            // 把对象转换成固定八列 TSV 并追加到文件末尾。
            writer.write(toLine(report));
            // 每条记录单独占一行。
            writer.newLine();
        }
        // 文件写成功后再更新内存索引，避免查询到尚未持久化的记录。
        reportsByDevice.computeIfAbsent(report.deviceSn(), ignored -> new ArrayList<>()).add(report);
    }

    @Override
    public synchronized Optional<ChargingReport> latest(String deviceSn) {
        // 按设备号取得该设备的顺序记录列表。
        List<ChargingReport> reports = reportsByDevice.get(deviceSn);
        // 从未上报时用 Optional.empty 明确表示没有值。
        if (reports == null || reports.isEmpty()) {
            return Optional.empty();
        }
        // 记录按追加顺序保存，所以最后一个元素就是最新状态。
        return Optional.of(reports.get(reports.size() - 1));
    }

    @Override
    public synchronized List<ChargingReport> history(String deviceSn, int limit) {
        // 查找指定设备的全部内存记录。
        List<ChargingReport> reports = reportsByDevice.get(deviceSn);
        // 没有记录时返回不可变空列表。
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        // 只截取列表末尾最多 limit 条；Math.max 防止起点小于 0。
        int from = Math.max(0, reports.size() - limit);
        // 复制 subList，避免 reverse 操作改变内部原始列表。
        List<ChargingReport> result = new ArrayList<>(reports.subList(from, reports.size()));
        // 原列表是旧到新，反转后满足接口要求的“最新记录在前”。
        java.util.Collections.reverse(result);
        // 返回只读副本，调用方不能修改仓储索引。
        return List.copyOf(result);
    }

    private void loadExisting() throws IOException {
        // lineNumber 用来在损坏时准确报告第几行。
        int lineNumber = 0;
        // 按 UTF-8 一次读取所有历史行，适合本项目的小数据量演示。
        for (String line : Files.readAllLines(storageFile, StandardCharsets.UTF_8)) {
            // 每遍历一行就递增真实文件行号。
            lineNumber++;
            // 空白行不代表业务记录，直接跳过。
            if (line.isBlank()) {
                continue;
            }
            try {
                // 把 TSV 行还原成 ChargingReport。
                ChargingReport report = fromLine(line);
                // 按设备号恢复到对应内存列表，并保持文件中的原始顺序。
                reportsByDevice.computeIfAbsent(report.deviceSn(), ignored -> new ArrayList<>()).add(report);
            } catch (RuntimeException e) {
                // 任意列数、枚举或数字错误都说明文件损坏，不能静默丢数据继续启动。
                throw new IOException("Corrupt persistence record at line " + lineNumber, e);
            }
        }
    }

    private static String toLine(ChargingReport report) {
        // String.join 使用制表符连接固定八列；字段顺序与 fromLine 完全对应。
        return String.join("\t",
                // 第 1 列：网关接收毫秒时间。
                Long.toString(report.receivedAt()),
                // 第 2 列：设备协议秒级时间。
                Long.toString(report.protocolTimestamp()),
                // 第 3 列：协议帧序号。
                Integer.toString(report.packageSequence()),
                // 第 4 列：设备 SN。
                report.deviceSn(),
                // 第 5 列：状态枚举名称。
                report.status().name(),
                // 第 6 列：电压。
                Double.toString(report.voltage()),
                // 第 7 列：电流。
                Double.toString(report.current()),
                // 第 8 列：故障码；空字符串仍保留这一列。
                report.faultCode());
    }

    private static ChargingReport fromLine(String line) {
        // limit=-1 会保留末尾空字段，确保无故障时仍得到第 8 列。
        String[] parts = line.split("\t", -1);
        // 列数不是 8 说明文件格式不完整或被人工错误修改。
        if (parts.length != 8) {
            throw new IllegalArgumentException("Expected 8 columns");
        }
        // 按 toLine 的相反顺序解析各列并重新构造领域对象。
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
