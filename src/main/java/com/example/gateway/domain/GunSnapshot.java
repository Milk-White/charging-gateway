package com.example.gateway.domain;

import java.util.List;

/** 一把充电枪及其点位数据；枪地址 0 表示充电桩公共信息。 */
public record GunSnapshot(int gunAddress, List<PointValue> points) {
    public GunSnapshot {
        if (gunAddress < 0) {
            throw new IllegalArgumentException("gunAddress must not be negative");
        }
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("points must not be empty");
        }
        points = List.copyOf(points);
    }
}
