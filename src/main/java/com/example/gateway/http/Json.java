package com.example.gateway.http;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.service.DeviceSessionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 演示项目使用的轻量 JSON 辅助类。
 *
 * <p>解析器只支持当前接口需要的扁平 JSON 对象和基础值，但会严格检查对象边界、
 * 字符串转义、数字格式、重复字段及尾随内容，避免正则提取造成非法 JSON 被接受。</p>
 */
final class Json {
    private Json() {
    }

    static Map<String, Object> object(String body) {
        return new Parser(body).parseObject();
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Missing JSON string: " + key);
        }
        return string;
    }

    static double number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Double number)) {
            throw new IllegalArgumentException("Missing JSON number: " + key);
        }
        return number;
    }

    static String report(ChargingReport report) {
        return "{" +
                "\"deviceSn\":\"" + escape(report.deviceSn()) + "\"," +
                "\"status\":\"" + report.status().name() + "\"," +
                "\"voltage\":" + format(report.voltage()) + "," +
                "\"current\":" + format(report.current()) + "," +
                "\"faultCode\":\"" + escape(report.faultCode()) + "\"," +
                "\"protocolTimestamp\":" + report.protocolTimestamp() + "," +
                "\"packageSequence\":" + report.packageSequence() + "," +
                "\"receivedAt\":" + report.receivedAt() +
                "}";
    }

    static String reports(List<ChargingReport> reports) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(report(reports.get(i)));
        }
        return out.append(']').toString();
    }

    static String strings(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(escape(values.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    static String sessions(List<DeviceSessionService.SessionView> values) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            var value = values.get(index);
            out.append("{\"deviceSn\":\"").append(escape(value.deviceSn()))
                    .append("\",\"loggedInAt\":").append(value.loggedInAt())
                    .append(",\"lastSeenAt\":").append(value.lastSeenAt()).append('}');
        }
        return out.append(']').toString();
    }

    static String error(String code, String message) {
        return "{\"error\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            if (text == null) {
                throw new IllegalArgumentException("JSON body must not be null");
            }
            this.text = text;
        }

        private Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                requireEnd();
                return Map.copyOf(values);
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                if (values.containsKey(key)) {
                    throw error("Duplicate JSON field: " + key);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                values.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    requireEnd();
                    return Map.copyOf(values);
                }
                expect(',');
            }
        }

        private Object parseValue() {
            if (peek('"')) {
                return parseString();
            }
            if (peek('-') || peekDigit()) {
                return parseNumber();
            }
            if (consumeLiteral("true")) {
                return Boolean.TRUE;
            }
            if (consumeLiteral("false")) {
                return Boolean.FALSE;
            }
            if (consumeLiteral("null")) {
                return NullValue.INSTANCE;
            }
            throw error("Unsupported JSON value");
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') {
                    return out.toString();
                }
                if (current < 0x20) {
                    throw error("Control character in JSON string");
                }
                if (current != '\\') {
                    out.append(current);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("Incomplete JSON escape");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseUnicodeEscape());
                    default -> throw error("Unsupported JSON escape: \\" + escaped);
                }
            }
            throw error("Unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > text.length()) {
                throw error("Incomplete Unicode escape");
            }
            String hex = text.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid Unicode escape");
            }
        }

        private Double parseNumber() {
            int start = position;
            consume('-');
            if (consume('0')) {
                if (peekDigit()) {
                    throw error("Leading zero in JSON number");
                }
            } else {
                requireDigits();
            }
            if (consume('.')) {
                requireDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                requireDigits();
            }
            try {
                return Double.valueOf(text.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("Invalid JSON number");
            }
        }

        private void requireDigits() {
            int start = position;
            while (peekDigit()) {
                position++;
            }
            if (start == position) {
                throw error("Expected digit");
            }
        }

        private boolean consumeLiteral(String literal) {
            if (text.startsWith(literal, position)) {
                position += literal.length();
                return true;
            }
            return false;
        }

        private boolean peek(char expected) {
            return position < text.length() && text.charAt(position) == expected;
        }

        private boolean peekDigit() {
            return position < text.length() && Character.isDigit(text.charAt(position));
        }

        private boolean consume(char expected) {
            if (peek(expected)) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private void requireEnd() {
            skipWhitespace();
            if (position != text.length()) {
                throw error("Trailing content after JSON object");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
    }

    private enum NullValue {
        INSTANCE
    }
}
