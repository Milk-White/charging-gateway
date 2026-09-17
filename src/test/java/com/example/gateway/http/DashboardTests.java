package com.example.gateway.http;

import java.util.List;

/** 内置监控页面与设备清单 JSON 的资源测试，由 AllTests 统一调用。 */
public final class DashboardTests {
    private DashboardTests() {
    }

    public static int run() {
        loadsDashboardResource();
        containsRequiredDashboardFeatures();
        serializesDeviceList();
        return 3;
    }

    private static void loadsDashboardResource() {
        String page = DashboardPage.content();
        check(page.contains("充电桩设备接入网关"), "dashboard title");
        check(page.contains("<canvas id=\"trendChart\""), "trend canvas");
    }

    private static void containsRequiredDashboardFeatures() {
        String page = DashboardPage.content();
        check(page.contains("/api/devices"), "device API usage");
        check(page.contains("/api/simulator/report"), "simulator API usage");
        check(!page.contains("https://"), "dashboard must not require remote assets");
    }

    private static void serializesDeviceList() {
        check(Json.strings(List.of("pile001", "pile002")).equals("[\"pile001\",\"pile002\"]"),
                "device list JSON");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }
}
