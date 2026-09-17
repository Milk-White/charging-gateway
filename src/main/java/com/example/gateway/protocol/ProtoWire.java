package com.example.gateway.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 项目内部使用的最小 Protobuf wire 编解码工具。
 *
 * <p>用途：仅实现本项目需要的 varint、fixed32、fixed64 和 length-delimited 类型，避免引入第三方
 * Protobuf 运行库。它不是完整的通用 Protobuf 实现。</p>
 */
final class ProtoWire {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private ProtoWire() {
    }

    /** 写入 int32：先写字段 tag，再使用 varint 写入值。 */
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
        // Protobuf fixed64 使用小端字节序，double 先转换为原始 64 位表示。
        out.writeBytes(ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(Double.doubleToRawLongBits(value))
                .array());
    }

    static void writeFloat(ByteArrayOutputStream out, int field, float value) {
        writeTag(out, field, FIXED32);
        out.writeBytes(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Float.floatToRawIntBits(value))
                .array());
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        // tag 的低 3 位保存 wire type，高位保存字段编号。
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        // 每个字节使用 7 位保存数据，最高位表示后面是否还有字节。
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
            // 防御性复制，避免调用方在解析期间修改原始字节数组。
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
            long encodedLength = readVarint();
            if (encodedLength > Integer.MAX_VALUE) {
                throw new ProtocolException("Protobuf length exceeds supported range");
            }
            int length = (int) encodedLength;
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

        float readFloat() {
            require(4);
            float value = ByteBuffer.wrap(data, position, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getFloat();
            position += 4;
            return value;
        }

        void skip(int wireType) {
            // 跳过未知字段，使新增字段不会破坏旧版本解析器。
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
                if (shift == 63 && (current & 0xfe) != 0) {
                    throw new ProtocolException("Malformed protobuf varint");
                }
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
            // 所有读取动作先检查边界，防止截断报文导致数组越界。
            if (length < 0 || length > data.length - position) {
                throw new ProtocolException("Truncated protobuf frame");
            }
        }
    }
}
