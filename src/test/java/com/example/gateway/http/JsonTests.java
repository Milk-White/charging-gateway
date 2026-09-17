package com.example.gateway.http;

import com.example.gateway.protocol.ChargingProtocolCodec;

/** JSON 和 HTTP 请求边界测试，由 AllTests 统一调用。 */
public final class JsonTests {
    private JsonTests() {
    }

    public static int run() {
        parsesValidObject();
        rejectsNonJsonAndTrailingContent();
        rejectsDuplicateFields();
        bodyLimitAccommodatesMaximumBase64Frame();
        return 4;
    }

    private static void parsesValidObject() {
        var object = Json.object("{\"deviceSn\":\"pile001\",\"voltage\":3.805e2}");
        check(Json.string(object, "deviceSn").equals("pile001"), "valid JSON string");
        check(Json.number(object, "voltage") == 380.5, "valid JSON number");
    }

    private static void rejectsNonJsonAndTrailingContent() {
        expect(IllegalArgumentException.class,
                () -> Json.object("not-json but contains \"deviceSn\":\"pile001\""));
        expect(IllegalArgumentException.class,
                () -> Json.object("{\"deviceSn\":\"pile001\"} trailing"));
    }

    private static void rejectsDuplicateFields() {
        expect(IllegalArgumentException.class,
                () -> Json.object("{\"deviceSn\":\"pile001\",\"deviceSn\":\"pile002\"}"));
    }

    private static void bodyLimitAccommodatesMaximumBase64Frame() {
        int maximumBase64Length = ((ChargingProtocolCodec.MAX_FRAME_LENGTH + 2) / 3) * 4;
        check(GatewayHttpServer.MAX_REQUEST_BODY_LENGTH > maximumBase64Length,
                "HTTP body limit must accommodate Base64 expansion and JSON wrapper");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + " but got " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " but no exception was thrown");
    }
}
