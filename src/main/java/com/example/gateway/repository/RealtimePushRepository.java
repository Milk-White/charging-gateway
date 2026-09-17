package com.example.gateway.repository;

import com.example.gateway.domain.RealtimePush;

import java.io.IOException;

/** 完整 103 枪/点位数据的持久化接口。 */
public interface RealtimePushRepository {
    void save(String deviceSn, int packageSequence, long receivedAt,
              RealtimePush push, byte[] encodedPayload) throws IOException;
}
