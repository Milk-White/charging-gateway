package com.example.gateway.domain;

import java.util.List;

/** 功能码 103 的完整业务数据：数据类别、采集时间、充电枪列表和点位列表。 */
public record RealtimePush(ReportKind kind, long recordTime, List<GunSnapshot> guns) {
    public RealtimePush {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (recordTime < 1) {
            throw new IllegalArgumentException("recordTime must be positive");
        }
        if (guns == null || guns.isEmpty()) {
            throw new IllegalArgumentException("guns must not be empty");
        }
        guns = List.copyOf(guns);
    }
}
