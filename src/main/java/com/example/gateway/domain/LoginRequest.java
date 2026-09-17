package com.example.gateway.domain;

/** 功能码 101 的登录业务数据。用户名与设备 SN 保持一致。 */
public record LoginRequest(String userName, String password) {
    public LoginRequest {
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("userName must not be blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
    }
}
