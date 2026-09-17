package com.example.gateway.domain;

/**
 * 协议中的一个点位值，对应文档 oneof Values 的 bool、int、float、double、string。
 */
public record PointValue(int pointAddress, Type type, Object value) {
    public enum Type {
        BOOLEAN, INTEGER, FLOAT, DOUBLE, STRING
    }

    public PointValue {
        if (pointAddress < 1) {
            throw new IllegalArgumentException("pointAddress must be positive");
        }
        if (type == null || value == null) {
            throw new IllegalArgumentException("point type and value are required");
        }
        boolean valid = switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Integer;
            case FLOAT -> value instanceof Float;
            case DOUBLE -> value instanceof Double;
            case STRING -> value instanceof String;
        };
        if (!valid) {
            throw new IllegalArgumentException("Point value does not match type " + type);
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("Double point value must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("Float point value must be finite");
        }
    }

    public static PointValue bool(int address, boolean value) {
        return new PointValue(address, Type.BOOLEAN, value);
    }

    public static PointValue integer(int address, int value) {
        return new PointValue(address, Type.INTEGER, value);
    }

    public static PointValue floating(int address, float value) {
        return new PointValue(address, Type.FLOAT, value);
    }

    public static PointValue decimal(int address, double value) {
        return new PointValue(address, Type.DOUBLE, value);
    }

    public static PointValue text(int address, String value) {
        return new PointValue(address, Type.STRING, value);
    }
}
