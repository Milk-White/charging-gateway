package com.example.gateway.domain;

/** 功能码 103 在不同运行阶段对应的数据类别。 */
public enum ReportKind {
    PUBLIC(1),
    REALTIME(2),
    IDLE(3),
    CHARGING(4),
    SPECIAL(5);

    private final int code;

    ReportKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ReportKind fromCode(int code) {
        for (ReportKind value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown report kind: " + code);
    }
}
