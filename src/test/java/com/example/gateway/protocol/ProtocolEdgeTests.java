package com.example.gateway.protocol;

import com.example.gateway.domain.ChargingReport;
import com.example.gateway.domain.ChargingStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** 协议边界测试，由 AllTests 统一调用。 */
public final class ProtocolEdgeTests {
    private ProtocolEdgeTests() {
    }

    public static int run() throws Exception {
        rejectsWrongWireType();
        wrapsOversizedLengthAsProtocolError();
        requiresPackageSequence();
        return 3;
    }

    private static void rejectsWrongWireType() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        ByteArrayOutputStream payload = validPayload();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x0A); // 字段 1 故意使用 length-delimited，而不是 varint。
        frame.write(42);
        writeRemainingEnvelope(frame, timestamp, payload.toByteArray());
        expect(ProtocolException.class,
                () -> new ChargingProtocolCodec("test-secret").decodeReport(frame.toByteArray()));
    }

    private static void wrapsOversizedLengthAsProtocolError() {
        long timestamp = Instant.now().getEpochSecond();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        ProtoWire.writeInt32(frame, 1, 1);
        ProtoWire.writeInt32(frame, 2, ChargingProtocolCodec.REALTIME_DATA_FUNCTION);
        ProtoWire.writeInt32(frame, 3, Math.toIntExact(timestamp));
        ProtoWire.writeBool(frame, 4, false);
        frame.write(0x52);
        frame.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08});
        expect(ProtocolException.class,
                () -> new ChargingProtocolCodec("test-secret").decodeReport(frame.toByteArray()));
    }

    private static void requiresPackageSequence() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeRemainingEnvelope(frame, timestamp, validPayload().toByteArray());
        expect(ProtocolException.class,
                () -> new ChargingProtocolCodec("test-secret").decodeReport(frame.toByteArray()));
    }

    private static ByteArrayOutputStream validPayload() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ProtoWire.writeString(payload, 1, "pile001");
        ProtoWire.writeInt32(payload, 2, ChargingStatus.CHARGING.code());
        ProtoWire.writeDouble(payload, 3, 380.5);
        ProtoWire.writeDouble(payload, 4, 32.25);
        ProtoWire.writeString(payload, 5, "");
        return payload;
    }

    private static void writeRemainingEnvelope(ByteArrayOutputStream frame, long timestamp, byte[] payload)
            throws Exception {
        ProtoWire.writeInt32(frame, 2, ChargingProtocolCodec.REALTIME_DATA_FUNCTION);
        ProtoWire.writeInt32(frame, 3, Math.toIntExact(timestamp));
        ProtoWire.writeBool(frame, 4, false);
        ProtoWire.writeBytes(frame, 10, payload);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        String signature = HexFormat.of().formatHex(
                md5.digest(("test-secret_" + timestamp).getBytes(StandardCharsets.US_ASCII)));
        ProtoWire.writeString(frame, 11, signature);
    }

    private static void expect(Class<? extends Throwable> expected, ThrowingRunnable action) {
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
