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
    // wire type 0：变长整数，适用于 int32、bool 等值。
    static final int VARINT = 0;
    // wire type 1：固定 8 字节，项目用它保存 double。
    static final int FIXED64 = 1;
    // wire type 2：长度前缀数据，适用于字符串、字节数组和嵌套消息。
    static final int LENGTH_DELIMITED = 2;
    // wire type 5：固定 4 字节，项目用它保存 float。
    static final int FIXED32 = 5;

    // 工具类只提供静态方法，不允许创建实例。
    private ProtoWire() {
    }

    /** 写入 int32：先写字段 tag，再使用 varint 写入值。 */
    static void writeInt32(ByteArrayOutputStream out, int field, int value) {
        // 先写 tag，让接收端知道字段编号和编码类型。
        writeTag(out, field, VARINT);
        // 与 0xffffffff 做 AND，把 Java 有符号 int 按 32 位无符号位模式写出。
        writeVarint(out, value & 0xffffffffL);
    }

    static void writeBool(ByteArrayOutputStream out, int field, boolean value) {
        // Protobuf 用整数 1 表示 true，用 0 表示 false。
        writeInt32(out, field, value ? 1 : 0);
    }

    static void writeString(ByteArrayOutputStream out, int field, String value) {
        // 协议统一使用 UTF-8，把字符串先转成字节再复用 writeBytes。
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) {
        // 字节数组和嵌套消息都使用 length-delimited 类型。
        writeTag(out, field, LENGTH_DELIMITED);
        // 在内容前先写 varint 长度，解析器据此知道要读取多少字节。
        writeVarint(out, value.length);
        // 长度后面紧跟实际内容。
        out.writeBytes(value);
    }

    static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        // double 对应 fixed64，所以 tag 的 wire type 是 1。
        writeTag(out, field, FIXED64);
        // 分配恰好 8 字节的临时缓冲区。
        out.writeBytes(ByteBuffer.allocate(8)
                // Protobuf fixed64 规定使用小端字节序。
                .order(ByteOrder.LITTLE_ENDIAN)
                // 保留 double 的原始 IEEE 754 位，不做数值转换。
                .putLong(Double.doubleToRawLongBits(value))
                // 取出写好的 8 字节并追加到输出流。
                .array());
    }

    static void writeFloat(ByteArrayOutputStream out, int field, float value) {
        // float 对应 fixed32，所以 tag 的 wire type 是 5。
        writeTag(out, field, FIXED32);
        // 分配 4 字节并按小端序保存 float 的原始 IEEE 754 位。
        out.writeBytes(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Float.floatToRawIntBits(value))
                .array());
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        // 字段编号左移 3 位，空出的低 3 位用来放 wire type。
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        // 只要 7 位以外还有数据，就继续写“后面还有字节”的中间字节。
        while ((value & ~0x7fL) != 0) {
            // 当前低 7 位保存数据，最高位置 1 表示后面仍有内容。
            out.write((int) ((value & 0x7f) | 0x80));
            // 无符号右移 7 位，准备处理剩余部分。
            value >>>= 7;
        }
        // 最后一个字节最高位为 0，告诉解析器 varint 到此结束。
        out.write((int) value);
    }

    static final class Reader {
        // data 保存待解析帧的私有副本。
        private final byte[] data;
        // position 指向下一个待读取字节，初始值默认为 0。
        private int position;

        Reader(byte[] data) {
            // 防御性复制，避免调用方在解析期间修改原数组造成结果不一致。
            this.data = Arrays.copyOf(data, data.length);
        }

        boolean hasNext() {
            // position 小于总长度，说明至少还有一个字节尚未解析。
            return position < data.length;
        }

        int readTag() {
            // tag 本身也使用 varint 编码。
            long tag = readVarint();
            // tag 不能为 0，也不能大到无法放入 Java int。
            if (tag == 0 || tag > Integer.MAX_VALUE) {
                throw new ProtocolException("Invalid protobuf tag: " + tag);
            }
            // 校验通过后安全地转成 int，调用方再拆字段编号和 wire type。
            return (int) tag;
        }

        int readInt32() {
            // int32 使用 varint；强制转 int 会保留低 32 位位模式。
            return (int) readVarint();
        }

        boolean readBool() {
            // Protobuf 约定 0 为 false，任何非 0 值都按 true 处理。
            return readVarint() != 0;
        }

        String readString() {
            // 先按 length-delimited 规则读取字节，再用 UTF-8 还原字符串。
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        byte[] readBytes() {
            // length-delimited 字段的第一个值是 varint 长度。
            long encodedLength = readVarint();
            // Java 数组长度使用 int，超出 int 范围的报文无法安全分配。
            if (encodedLength > Integer.MAX_VALUE) {
                throw new ProtocolException("Protobuf length exceeds supported range");
            }
            // 前面的范围检查保证这里可以安全转换。
            int length = (int) encodedLength;
            // 在复制之前确认剩余字节足够，避免数组越界。
            require(length);
            // 复制当前字段内容，返回值与底层帧相互独立。
            byte[] result = Arrays.copyOfRange(data, position, position + length);
            // 游标移动到当前字段之后，下一次读取从新位置开始。
            position += length;
            // 返回当前 length-delimited 字段内容。
            return result;
        }

        double readDouble() {
            // double 固定需要 8 个字节，先做边界检查。
            require(8);
            // 从当前位置包裹 8 字节，并按 Protobuf 小端序读取。
            double value = ByteBuffer.wrap(data, position, 8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getDouble();
            // 读取完成后把游标向后移动 8 字节。
            position += 8;
            // 返回还原后的 double。
            return value;
        }

        float readFloat() {
            // float 固定需要 4 个字节，先做边界检查。
            require(4);
            // 从当前位置按小端序还原 float。
            float value = ByteBuffer.wrap(data, position, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getFloat();
            // 读取完成后把游标向后移动 4 字节。
            position += 4;
            // 返回还原后的 float。
            return value;
        }

        void skip(int wireType) {
            // 按未知字段自己的 wire type 消耗正确字节数，保持游标同步。
            switch (wireType) {
                // varint 长度不固定，直接读取一次即可跳过。
                case VARINT -> readVarint();
                // fixed64 固定跳过 8 字节。
                case FIXED64 -> advance(8);
                // length-delimited 先读长度，再跳过对应内容。
                case LENGTH_DELIMITED -> advance(Math.toIntExact(readVarint()));
                // fixed32 固定跳过 4 字节。
                case FIXED32 -> advance(4);
                // 3、4 等已废弃 wire type 本项目不支持，立即拒绝。
                default -> throw new ProtocolException("Unsupported protobuf wire type: " + wireType);
            }
        }

        private long readVarint() {
            // result 累积每个字节贡献的 7 位数据。
            long result = 0;
            // long 最多 64 位，因此 shift 每次增加 7，最多循环 10 次。
            for (int shift = 0; shift < 64; shift += 7) {
                // 每读一个字节前都先确认帧没有截断。
                require(1);
                // & 0xff 把 Java 有符号 byte 转为 0 到 255 的整数。
                int current = data[position++] & 0xff;
                // 第 10 个字节最多只能携带 1 位，其他位非 0 说明 varint 溢出。
                if (shift == 63 && (current & 0xfe) != 0) {
                    throw new ProtocolException("Malformed protobuf varint");
                }
                // 取当前低 7 位，移动到对应位置后合并进结果。
                result |= (long) (current & 0x7f) << shift;
                // 最高位为 0 表示这是最后一个字节，可以返回结果。
                if ((current & 0x80) == 0) {
                    return result;
                }
            }
            // 循环结束仍未遇到结束字节，说明输入不是合法 varint。
            throw new ProtocolException("Malformed protobuf varint");
        }

        private void advance(int length) {
            // 跳过前也要检查剩余长度，防止恶意长度越界。
            require(length);
            // 只移动游标，不复制不需要的未知字段内容。
            position += length;
        }

        private void require(int length) {
            // 负长度非法；长度超过剩余字节则说明协议帧被截断。
            if (length < 0 || length > data.length - position) {
                throw new ProtocolException("Truncated protobuf frame");
            }
        }
    }
}
