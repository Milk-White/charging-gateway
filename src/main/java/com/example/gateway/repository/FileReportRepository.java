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

public final class FileReportRepository implements ReportRepository {
    private final Path storageFile;
    private final Map<String, List<ChargingReport>> reportsByDevice = new ConcurrentHashMap<>();

    public FileReportRepository(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        this.storageFile = dataDirectory.resolve("reports.tsv");
        if (Files.exists(storageFile)) {
            loadExisting();
        }
    }

    @Override
    public synchronized void save(ChargingReport report) throws IOException {
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
                throw new IOException("Corrupt persistence record at line " + lineNumber, e);
            }
        }
    }

    private static String toLine(ChargingReport report) {
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
