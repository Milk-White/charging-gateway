package com.example.gateway.service;

import com.example.gateway.domain.LoginRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 功能码 101 登录态管理：验证凭据、刷新最后活动时间、5 分钟无消息自动失效。 */
public final class DeviceSessionService {
    public static final long DEFAULT_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();
    private final Map<String, String> credentials;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long timeoutMillis;

    public DeviceSessionService(Map<String, String> credentials) {
        this(credentials, System::currentTimeMillis, DEFAULT_TIMEOUT_MILLIS);
    }

    public DeviceSessionService(Map<String, String> credentials, LongSupplier clock, long timeoutMillis) {
        if (credentials == null || credentials.isEmpty()) {
            throw new IllegalArgumentException("At least one device credential is required");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.credentials = Map.copyOf(credentials);
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
    }

    public LoginResult login(String deviceSn, LoginRequest request) {
        if (!credentials.containsKey(deviceSn)) {
            return new LoginResult(false, 1001, "DEVICE_NOT_REGISTERED");
        }
        if (!deviceSn.equals(request.userName())
                || !constantTimeEquals(credentials.get(deviceSn), request.password())) {
            return new LoginResult(false, 1002, "INVALID_CREDENTIALS");
        }
        long now = clock.getAsLong();
        sessions.put(deviceSn, new Session(now, now));
        return new LoginResult(true, 0, "OK");
    }

    public void requireLoggedIn(String deviceSn) {
        Session session = sessions.get(deviceSn);
        if (session == null) {
            throw new ValidationException("Device is not logged in: " + deviceSn);
        }
        long now = clock.getAsLong();
        if (now - session.lastSeenAt() >= timeoutMillis) {
            sessions.remove(deviceSn, session);
            throw new ValidationException("Device login has expired: " + deviceSn);
        }
    }

    public void touch(String deviceSn) {
        requireLoggedIn(deviceSn);
        sessions.computeIfPresent(deviceSn,
                (ignored, session) -> new Session(session.loggedInAt(), clock.getAsLong()));
    }

    public boolean isLoggedIn(String deviceSn) {
        try {
            requireLoggedIn(deviceSn);
            return true;
        } catch (ValidationException exception) {
            return false;
        }
    }

    public List<SessionView> activeSessions() {
        List<SessionView> result = new ArrayList<>();
        for (String deviceSn : sessions.keySet()) {
            if (isLoggedIn(deviceSn)) {
                Session session = sessions.get(deviceSn);
                result.add(new SessionView(deviceSn, session.loggedInAt(), session.lastSeenAt()));
            }
        }
        result.sort(Comparator.comparing(SessionView::deviceSn));
        return List.copyOf(result);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int difference = expected.length() ^ actual.length();
        int length = Math.max(expected.length(), actual.length());
        for (int index = 0; index < length; index++) {
            char left = index < expected.length() ? expected.charAt(index) : 0;
            char right = index < actual.length() ? actual.charAt(index) : 0;
            difference |= left ^ right;
        }
        return difference == 0;
    }

    public record LoginResult(boolean success, int resultCode, String message) {
    }

    public record SessionView(String deviceSn, long loggedInAt, long lastSeenAt) {
    }

    private record Session(long loggedInAt, long lastSeenAt) {
    }
}
