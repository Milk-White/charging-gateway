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
    // 协议要求设备连续 5 分钟没有合法消息就必须重新登录。
    public static final long DEFAULT_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();
    // credentials 保存“设备 SN -> 登录密码”，创建后不可变。
    private final Map<String, String> credentials;
    // sessions 保存当前已登录设备；ConcurrentHashMap 允许 HTTP/MQTT 多线程安全访问。
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    // clock 提供当前毫秒数；测试时可以注入假时钟模拟 5 分钟超时。
    private final LongSupplier clock;
    // timeoutMillis 是会话超时时长，生产默认 5 分钟，测试可传更短时间。
    private final long timeoutMillis;

    public DeviceSessionService(Map<String, String> credentials) {
        // 正常运行使用系统时钟和默认 5 分钟超时，再交给完整构造方法统一初始化。
        this(credentials, System::currentTimeMillis, DEFAULT_TIMEOUT_MILLIS);
    }

    public DeviceSessionService(Map<String, String> credentials, LongSupplier clock, long timeoutMillis) {
        // 没有任何合法设备时系统无法执行登录，因此直接拒绝启动。
        if (credentials == null || credentials.isEmpty()) {
            throw new IllegalArgumentException("At least one device credential is required");
        }
        // 超时时长必须为正数，否则设备会刚登录就失效。
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        // 复制凭据，防止调用方之后修改原 Map 绕过登录规则。
        this.credentials = Map.copyOf(credentials);
        // 保存时钟对象，后面所有登录和心跳时间都从同一个时钟读取。
        this.clock = clock;
        // 保存会话超时时长。
        this.timeoutMillis = timeoutMillis;
    }

    public LoginResult login(String deviceSn, LoginRequest request) {
        // 第一步检查路径或 MQTT 主题中的设备 SN 是否已经平台注册。
        if (!credentials.containsKey(deviceSn)) {
            return new LoginResult(false, 1001, "DEVICE_NOT_REGISTERED");
        }
        // 用户名必须等于设备 SN，同时密码必须和配置值一致。
        if (!deviceSn.equals(request.userName())
                || !constantTimeEquals(credentials.get(deviceSn), request.password())) {
            return new LoginResult(false, 1002, "INVALID_CREDENTIALS");
        }
        // 登录成功时只读取一次当前时间，保证登录时间和最后活动时间完全相同。
        long now = clock.getAsLong();
        // 用新会话覆盖该设备的旧会话，相当于允许设备重新登录。
        sessions.put(deviceSn, new Session(now, now));
        // 结果码 0 代表登录成功，DeviceProtocolService 会把它编码成 101 响应帧。
        return new LoginResult(true, 0, "OK");
    }

    public void requireLoggedIn(String deviceSn) {
        // 先按设备号查找内存中的登录会话。
        Session session = sessions.get(deviceSn);
        // 找不到说明设备从未登录，或者超时后已经被清除。
        if (session == null) {
            throw new ValidationException("Device is not logged in: " + deviceSn);
        }
        // 获取当前时间，用它减去最后活动时间判断是否超时。
        long now = clock.getAsLong();
        // 大于等于超时时长就视为失效，边界时刻也不再允许上报。
        if (now - session.lastSeenAt() >= timeoutMillis) {
            // remove(key, value) 只删除刚才读到的旧会话，避免并发心跳刚更新后被误删。
            sessions.remove(deviceSn, session);
            // 抛出统一业务异常，由上层转换为 NOT_LOGGED_IN 响应。
            throw new ValidationException("Device login has expired: " + deviceSn);
        }
    }

    public void touch(String deviceSn) {
        // 刷新前必须先确认会话存在且未超时。
        requireLoggedIn(deviceSn);
        // 保留最初登录时间，只把最后活动时间更新为当前毫秒数。
        sessions.computeIfPresent(deviceSn,
                (ignored, session) -> new Session(session.loggedInAt(), clock.getAsLong()));
    }

    public boolean isLoggedIn(String deviceSn) {
        try {
            // 复用 requireLoggedIn，确保这里也会执行 5 分钟超时判断。
            requireLoggedIn(deviceSn);
            // 没有抛异常就说明会话仍有效。
            return true;
        } catch (ValidationException exception) {
            // 未登录或已超时都统一返回 false，便于网页显示。
            return false;
        }
    }

    public List<SessionView> activeSessions() {
        // 先用可变列表收集有效会话，最后再返回只读结果。
        List<SessionView> result = new ArrayList<>();
        // 遍历当前会话表中的每个设备号。
        for (String deviceSn : sessions.keySet()) {
            // isLoggedIn 会顺便清理已经超时的会话。
            if (isLoggedIn(deviceSn)) {
                // 再读取一次会话，取得登录时间和最后活动时间。
                Session session = sessions.get(deviceSn);
                // 转成公开的 SessionView，避免把内部 Session 对象直接暴露出去。
                result.add(new SessionView(deviceSn, session.loggedInAt(), session.lastSeenAt()));
            }
        }
        // 按设备号排序，让接口每次返回顺序一致，方便页面展示和测试。
        result.sort(Comparator.comparing(SessionView::deviceSn));
        // 返回不可修改副本，调用方不能破坏服务内部状态。
        return List.copyOf(result);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        // 任意一边为空都不可能是合法密码。
        if (expected == null || actual == null) {
            return false;
        }
        // 先把长度差异混入结果，但不因为长度不同而提前结束比较。
        int difference = expected.length() ^ actual.length();
        // 循环次数固定为两者较长长度，减少密码长度造成的时间差异。
        int length = Math.max(expected.length(), actual.length());
        for (int index = 0; index < length; index++) {
            // 超出较短字符串的位置用 0 补齐，保证循环仍继续。
            char left = index < expected.length() ? expected.charAt(index) : 0;
            // actual 也采用相同补齐规则。
            char right = index < actual.length() ? actual.charAt(index) : 0;
            // XOR 得到当前字符差异，再用 OR 累积，过程中不提前返回。
            difference |= left ^ right;
        }
        // 所有字符及长度都一致时 difference 才会是 0。
        return difference == 0;
    }

    // 登录动作的返回值：是否成功、协议结果码以及给设备看的结果消息。
    public record LoginResult(boolean success, int resultCode, String message) {
    }

    // 提供给 /api/sessions 的只读会话视图。
    public record SessionView(String deviceSn, long loggedInAt, long lastSeenAt) {
    }

    // 服务内部会话对象：记录首次登录时间和最近一次合法消息时间。
    private record Session(long loggedInAt, long lastSeenAt) {
    }
}
