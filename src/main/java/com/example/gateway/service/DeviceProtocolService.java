package com.example.gateway.service;

import com.example.gateway.domain.ChargingPointAddresses;
import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;
import com.example.gateway.domain.LoginRequest;
import com.example.gateway.domain.PointValue;
import com.example.gateway.domain.RealtimePush;
import com.example.gateway.protocol.ChargingProtocolCodec;
import com.example.gateway.protocol.ChargingValueCodec;
import com.example.gateway.protocol.ProtocolEnvelope;
import com.example.gateway.protocol.ProtocolException;
import com.example.gateway.repository.RealtimePushRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** 编排 101 登录、102 心跳和 103 完整实时数据处理，并返回正式协议响应帧。 */
public final class DeviceProtocolService {
    private static final long MAX_PAST_SECONDS = 300;
    private static final long MAX_FUTURE_SECONDS = 60;
    private final ChargingProtocolCodec protocolCodec;
    private final ChargingValueCodec valueCodec;
    private final DeviceSessionService sessions;
    private final ReportService reportService;
    private final RealtimePushRepository realtimeRepository;

    public DeviceProtocolService(ChargingProtocolCodec protocolCodec, ChargingValueCodec valueCodec,
                                 DeviceSessionService sessions, ReportService reportService,
                                 RealtimePushRepository realtimeRepository) {
        this.protocolCodec = protocolCodec;
        this.valueCodec = valueCodec;
        this.sessions = sessions;
        this.reportService = reportService;
        this.realtimeRepository = realtimeRepository;
    }

    public HandledMessage handle(String deviceSn, byte[] frame) throws IOException {
        ProtocolEnvelope envelope = protocolCodec.decodeEnvelope(frame);
        if (envelope.response()) {
            throw new ProtocolException("Gateway accepts request frames only");
        }
        try {
            return switch (envelope.functionCode()) {
                case ChargingProtocolCodec.LOGIN_FUNCTION -> login(deviceSn, frame, envelope);
                case ChargingProtocolCodec.HEARTBEAT_FUNCTION -> heartbeat(deviceSn, envelope);
                case ChargingProtocolCodec.REALTIME_DATA_FUNCTION -> realtime(deviceSn, envelope);
                default -> result(envelope, 1005, "UNSUPPORTED_FUNCTION", Optional.empty());
            };
        } catch (ProtocolException exception) {
            return result(envelope, 2001, "INVALID_PROTOCOL: " + exception.getMessage(), Optional.empty());
        } catch (ValidationException | IllegalArgumentException exception) {
            return result(envelope, 2002, "INVALID_DATA: " + exception.getMessage(), Optional.empty());
        }
    }

    public DeviceSessionService sessions() {
        return sessions;
    }

    private HandledMessage login(String deviceSn, byte[] frame, ProtocolEnvelope envelope) {
        LoginRequest request = protocolCodec.decodeLoginRequest(frame);
        DeviceSessionService.LoginResult login = sessions.login(deviceSn, request);
        return result(envelope, login.resultCode(), login.message(), Optional.empty());
    }

    private HandledMessage heartbeat(String deviceSn, ProtocolEnvelope envelope) {
        try {
            sessions.touch(deviceSn);
            return result(envelope, 0, "OK", Optional.empty());
        } catch (ValidationException exception) {
            return result(envelope, 1003, "NOT_LOGGED_IN", Optional.empty());
        }
    }

    private HandledMessage realtime(String deviceSn, ProtocolEnvelope envelope) throws IOException {
        try {
            sessions.requireLoggedIn(deviceSn);
        } catch (ValidationException exception) {
            return result(envelope, 1003, "NOT_LOGGED_IN", Optional.empty());
        }
        RealtimePush push = valueCodec.decode(envelope.data());
        validateTime(push.recordTime());
        long receivedAt = Instant.now().toEpochMilli();
        Optional<ChargingReport> summary = toSummary(deviceSn, envelope, push, receivedAt);
        if (summary.isPresent()) {
            reportService.validateDecoded(summary.get());
        }
        realtimeRepository.save(deviceSn, envelope.packageSequence(), receivedAt, push, envelope.data());
        if (summary.isPresent()) {
            reportService.acceptDecoded(summary.get());
        }
        sessions.touch(deviceSn);
        return result(envelope, 0, "OK", summary);
    }

    private Optional<ChargingReport> toSummary(String deviceSn, ProtocolEnvelope envelope,
                                               RealtimePush push, long receivedAt) {
        PointValue statusPoint = find(push, ChargingPointAddresses.STATUS);
        PointValue voltagePoint = find(push, ChargingPointAddresses.VOLTAGE);
        PointValue currentPoint = find(push, ChargingPointAddresses.CURRENT);
        if (statusPoint == null || voltagePoint == null || currentPoint == null) {
            return Optional.empty();
        }
        ChargingStatus status = status(statusPoint);
        double voltage = number(voltagePoint, "voltage");
        double current = number(currentPoint, "current");
        PointValue faultPoint = find(push, ChargingPointAddresses.FAULT_CODE);
        String fault = faultPoint == null ? "" : String.valueOf(faultPoint.value());
        return Optional.of(new ChargingReport(deviceSn, status, voltage, current, fault,
                envelope.timestamp(), envelope.packageSequence(), receivedAt));
    }

    private static PointValue find(RealtimePush push, int address) {
        return push.guns().stream().flatMap(gun -> gun.points().stream())
                .filter(point -> point.pointAddress() == address).findFirst().orElse(null);
    }

    private static ChargingStatus status(PointValue point) {
        try {
            if (point.value() instanceof String value) {
                return ChargingStatus.valueOf(value.toUpperCase());
            }
            if (point.value() instanceof Integer value) {
                return ChargingStatus.fromCode(value);
            }
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Invalid status point: " + point.value());
        }
        throw new ValidationException("Status point must be string or integer");
    }

    private static double number(PointValue point, String name) {
        if (point.value() instanceof Number number) {
            return number.doubleValue();
        }
        throw new ValidationException(name + " point must be numeric");
    }

    private static void validateTime(long timestamp) {
        long now = Instant.now().getEpochSecond();
        if (timestamp < now - MAX_PAST_SECONDS || timestamp > now + MAX_FUTURE_SECONDS) {
            throw new ValidationException("Realtime record time is outside the accepted window");
        }
    }

    private HandledMessage result(ProtocolEnvelope request, int code, String message,
                                  Optional<ChargingReport> report) {
        return new HandledMessage(protocolCodec.encodeResponse(request, code, message),
                request.functionCode(), code, message, report);
    }

    public record HandledMessage(byte[] responseFrame, int functionCode, int resultCode,
                                 String message, Optional<ChargingReport> report) {
        public HandledMessage {
            responseFrame = responseFrame.clone();
        }

        @Override
        public byte[] responseFrame() {
            return responseFrame.clone();
        }
    }
}
