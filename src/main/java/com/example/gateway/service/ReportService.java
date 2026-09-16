package com.example.gateway.service;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.repository.ReportRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ReportService {
    private static final Pattern SN = Pattern.compile("[0-9A-Za-z]{1,32}");
    private static final Pattern FAULT_CODE = Pattern.compile("[0-9A-Fa-f]{4}");
    private static final long MAX_PAST_SECONDS = 300;
    private static final long MAX_FUTURE_SECONDS = 60;

    private final ChargingProtocolCodec codec;
    private final ReportRepository repository;

    public ReportService(ChargingProtocolCodec codec, ReportRepository repository) {
        this.codec = codec;
        this.repository = repository;
    }

    public ChargingReport acceptFrame(byte[] frame) throws IOException {
        ChargingReport report = codec.decodeReport(frame);
        validate(report);
        repository.save(report);
        return report;
    }

    public Optional<ChargingReport> latest(String deviceSn) {
        validateSn(deviceSn);
        return repository.latest(deviceSn);
    }

    public List<ChargingReport> history(String deviceSn, int limit) {
        validateSn(deviceSn);
        if (limit < 1 || limit > 1_000) {
            throw new ValidationException("limit must be between 1 and 1000");
        }
        return repository.history(deviceSn, limit);
    }

    private static void validate(ChargingReport report) {
        validateSn(report.deviceSn());
        validateRange("voltage", report.voltage(), 0, 1_000);
        validateRange("current", report.current(), 0, 1_000);

        String faultCode = report.faultCode();
        if (!faultCode.isEmpty() && !FAULT_CODE.matcher(faultCode).matches()) {
            throw new ValidationException("faultCode must be empty or four hexadecimal characters");
        }
        if (report.status() == ChargingStatus.FAULT && faultCode.isEmpty()) {
            throw new ValidationException("faultCode is required when status is FAULT");
        }

        long now = Instant.now().getEpochSecond();
        if (report.protocolTimestamp() < now - MAX_PAST_SECONDS
                || report.protocolTimestamp() > now + MAX_FUTURE_SECONDS) {
            throw new ValidationException("protocol timestamp is outside the accepted clock-skew window");
        }
    }

    private static void validateSn(String deviceSn) {
        if (deviceSn == null || !SN.matcher(deviceSn).matches()) {
            throw new ValidationException("deviceSn must contain 1-32 ASCII letters or digits");
        }
    }

    private static void validateRange(String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max);
        }
    }
}
