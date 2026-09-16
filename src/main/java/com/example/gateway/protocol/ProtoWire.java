package com.example.gateway.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ProtoWire {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private ProtoWire() {
    }

    static void writeInt32(ByteArrayOutputStream out, int field, int value) {
        writeTag(out, field, VARINT);
        writeVarint(out, value & 0xffffffffL);
    }

    static void writeBool(ByteArrayOutputStream out, int field, boolean value) {
        writeInt32(out, field, value ? 1 : 0);
    }

    static void writeString(ByteArrayOutputStream out, int field, String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) {
        writeTag(out, field, LENGTH_DELIMITED);
        writeVarint(out, value.length);
        out.writeBytes(value);
    }

    static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeTag(out, field, FIXED64);
        out.writeBytes(ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(Double.doubleToRawLongBits(value))
                .array());
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = Arrays.copyOf(data, data.length);
        }

        boolean hasNext() {
            return position < data.length;
        }

        int readTag() {
            long tag = readVarint();
            if (tag == 0 || tag > Integer.MAX_VALUE) {
                throw new ProtocolException("Invalid protobuf tag: " + tag);
            }
            return (int) tag;
        }

        int readInt32() {
            return (int) readVarint();
        }

        boolean readBool() {
            return readVarint() != 0;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        byte[] readBytes() {
            int length = Math.toIntExact(readVarint());
            require(length);
            byte[] result = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return result;
        }

        double readDouble() {
            require(8);
            double value = ByteBuffer.wrap(data, position, 8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getDouble();
            position += 8;
            return value;
        }

        void skip(int wireType) {
            switch (wireType) {
                case VARINT -> readVarint();
                case FIXED64 -> advance(8);
                case LENGTH_DELIMITED -> advance(Math.toIntExact(readVarint()));
                case FIXED32 -> advance(4);
                default -> throw new ProtocolException("Unsupported protobuf wire type: " + wireType);
            }
        }

        private long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int current = data[position++] & 0xff;
                result |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) {
                    return result;
                }
            }
            throw new ProtocolException("Malformed protobuf varint");
        }

        private void advance(int length) {
            require(length);
            position += length;
        }

        private void require(int length) {
            if (length < 0 || position + length > data.length) {
                throw new ProtocolException("Truncated protobuf frame");
            }
        }
    }
}
