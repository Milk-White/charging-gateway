package com.example.gateway.simulator;

import com.example.gateway.domain.ReportKind;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** 把协议文档中的登录、心跳、周期推送、变化推送和重试时间集中成可测试规则。 */
public final class PushSchedulePolicy {
    public static final Duration LOGIN_RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration LOGIN_RETRY_DELAY = Duration.ofSeconds(30);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    public Duration periodicInterval(ReportKind kind) {
        return switch (kind) {
            case PUBLIC -> Duration.ofMinutes(60);
            case REALTIME -> Duration.ofSeconds(5);
            case IDLE -> Duration.ofMinutes(5);
            case CHARGING -> Duration.ofMinutes(15);
            case SPECIAL -> Duration.ofMinutes(30);
        };
    }

    public Duration changeInterval(ReportKind kind) {
        return switch (kind) {
            case PUBLIC -> Duration.ofSeconds(15);
            case CHARGING -> Duration.ofSeconds(30);
            case REALTIME -> Duration.ofSeconds(5);
            case IDLE, SPECIAL -> Duration.ZERO;
        };
    }

    public Duration retryDelay(ReportKind kind, boolean changePush) {
        if (kind == ReportKind.CHARGING && changePush) {
            return Duration.ofSeconds(15);
        }
        return Duration.ofMinutes(1);
    }

    public int maxRetries(ReportKind kind, boolean changePush) {
        return kind == ReportKind.IDLE ? 3 : Integer.MAX_VALUE;
    }

    public boolean isSpecialFixedPoint(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return utc.getSecond() == 0 && (utc.getMinute() == 0 || utc.getMinute() == 30);
    }
}
