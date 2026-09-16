package com.example.gateway.http;

import com.example.gateway.domain.ChargingReport;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    static String error(String code, String message) {
        return "{\"error\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
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
