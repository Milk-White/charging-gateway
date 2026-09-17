package com.example.gateway.protocol;

import java.util.Arrays;

/** 外层协议头和业务数据。 */
public record ProtocolEnvelope(
        int packageSequence,
        int functionCode,
        long timestamp,
        boolean response,
        int resultCode,
        String resultMessage,
        byte[] data
) {
    public ProtocolEnvelope {
        resultMessage = resultMessage == null ? "" : resultMessage;
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
