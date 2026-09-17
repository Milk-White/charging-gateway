package com.example.gateway.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从 classpath 加载内置监控大屏，保持页面资源与 Java 服务一起交付。 */
final class DashboardPage {
    private static final String CONTENT = load();

    private DashboardPage() {
    }

    static String content() {
        return CONTENT;
    }

    private static String load() {
        try (InputStream input = DashboardPage.class.getResourceAsStream("/web/dashboard.html")) {
            if (input == null) {
                throw new IllegalStateException("Missing dashboard resource: /web/dashboard.html");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load dashboard resource", exception);
        }
    }
}
