package com.example.gateway.domain;

public record ChargingReport(
        String deviceSn,
        ChargingStatus status,
        double voltage,
        double current,
        String faultCode,
        long protocolTimestamp,
        int packageSequence,
        long receivedAt
) {
}
