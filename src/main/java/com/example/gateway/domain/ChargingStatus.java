package com.example.gateway.domain;

public enum ChargingStatus {
    IDLE(0),
    CHARGING(1),
    FAULT(2),
    OFFLINE(3);

    private final int code;

    ChargingStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ChargingStatus fromCode(int code) {
        for (ChargingStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported charging status: " + code);
    }
}
