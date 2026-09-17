package com.example.gateway.http;

import com.example.gateway.domain.ChargingReport;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 演示项目使用的轻量 JSON 辅助类。
 *
 * <p>用途：解析模拟接口所需的少量固定字段，并生成统一 JSON 响应。它不是完整 JSON
 * 解析器；生产项目应替换为经过验证的 JSON 库。</p>
 */
final class Json {
    private Json() {
    }

    static String string(String body, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing JSON string: " + key);
        }
        return unescape(matcher.group(1));
    }

    static double number(String body, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)")
                .matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing JSON number: " + key);
        }
        return Double.parseDouble(matcher.group(1));
    }

    static String report(ChargingReport report) {
        // 将领域对象转换为接口返回格式，所有接口复用同一字段命名。
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
        // 历史查询返回 JSON 数组，数组中的每一项复用 report 方法。
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(report(reports.get(i)));
        }
        return out.append(']').toString();
    }

    static String error(String code, String message) {
        // 错误响应统一包含稳定错误码和可读错误信息。
        return "{\"error\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        // 输出 JSON 前转义可能破坏字符串结构的特殊字符。
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
