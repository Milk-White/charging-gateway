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
    private final Path storageFile;

    public FileRealtimePushRepository(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        this.storageFile = dataDirectory.resolve("realtime-pushes.tsv");
    }

    @Override
    public synchronized void save(String deviceSn, int packageSequence, long receivedAt,
                                  RealtimePush push, byte[] encodedPayload) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.join("\t",
                    Long.toString(receivedAt),
                    Long.toString(push.recordTime()),
                    Integer.toString(packageSequence),
                    deviceSn,
                    push.kind().name(),
                    Integer.toString(push.guns().size()),
                    Base64.getEncoder().encodeToString(encodedPayload)));
            writer.newLine();
        }
    }
}
