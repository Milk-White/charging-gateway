package com.example.gateway.repository;

import com.example.gateway.domain.RealtimePush;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/**
 * 完整 103 数据追加保存到 realtime-pushes.tsv；最后一列可无损恢复枪与点位结构。
 */
public final class FileRealtimePushRepository implements RealtimePushRepository {
    // 完整点位数据固定保存到 data/realtime-pushes.tsv。
    private final Path storageFile;

    public FileRealtimePushRepository(Path dataDirectory) throws IOException {
        // 确保 data 目录存在。
        Files.createDirectories(dataDirectory);
        // 保存完整 103 的文件和 reports.tsv 分开，避免摘要丢失其他点位。
        this.storageFile = dataDirectory.resolve("realtime-pushes.tsv");
    }

    @Override
    public synchronized void save(String deviceSn, int packageSequence, long receivedAt,
                                  RealtimePush push, byte[] encodedPayload) throws IOException {
        // synchronized 防止 HTTP 和 MQTT 同时追加时两行内容交叉。
        try (BufferedWriter writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            // 每条完整 103 保存七列，最后一列 Base64 可无损还原原始 Data。
            writer.write(String.join("\t",
                    // 第 1 列：网关接收毫秒时间。
                    Long.toString(receivedAt),
                    // 第 2 列：设备采集秒级时间。
                    Long.toString(push.recordTime()),
                    // 第 3 列：外层协议帧序号。
                    Integer.toString(packageSequence),
                    // 第 4 列：设备 SN。
                    deviceSn,
                    // 第 5 列：五种数据类别之一。
                    push.kind().name(),
                    // 第 6 列：本次报文包含的充电枪数量。
                    Integer.toString(push.guns().size()),
                    // 第 7 列：完整枪与点位载荷，Base64 确保制表符和换行不会破坏 TSV。
                    Base64.getEncoder().encodeToString(encodedPayload)));
            // 当前记录结束，下一次保存从新行开始。
            writer.newLine();
        }
    }
}
