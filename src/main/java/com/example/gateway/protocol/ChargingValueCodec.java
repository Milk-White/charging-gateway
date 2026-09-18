package com.example.gateway.protocol;

import com.example.gateway.domain.GunSnapshot;
import com.example.gateway.domain.PointValue;
import com.example.gateway.domain.RealtimePush;
import com.example.gateway.domain.ReportKind;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 编解码文档中的“充电枪列表 -> 点位列表 -> oneof 值”业务结构。 */
public final class ChargingValueCodec {
    public byte[] encode(RealtimePush push) {
        // ByteArrayOutputStream 用来逐字段拼接 103 的 Data 字节，不需要预先计算长度。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 字段 1 是可重复的枪列表，每把枪都编码成一个嵌套 length-delimited 消息。
        for (GunSnapshot gun : push.guns()) {
            ProtoWire.writeBytes(out, 1, encodeGun(gun));
        }
        // 字段 2 保存设备采集时间；协议限定为无符号 32 位秒值。
        ProtoWire.writeInt32(out, 2, Math.toIntExact(push.recordTime()));
        // 字段 3 保存 PUBLIC/REALTIME/IDLE/CHARGING/SPECIAL 的数字代码。
        ProtoWire.writeInt32(out, 3, push.kind().code());
        // 返回完整的 103 业务数据字节，外层帧由 ChargingProtocolCodec 继续包装。
        return out.toByteArray();
    }

    public RealtimePush decode(byte[] bytes) {
        // guns 收集所有重复出现的字段 1。
        List<GunSnapshot> guns = new ArrayList<>();
        // 0 用作“尚未读到 recordTime”的标记，因为合法时间戳必须大于 0。
        long recordTime = 0;
        // null 用作“尚未读到数据类别”的标记。
        ReportKind kind = null;
        // Reader 按 Protobuf wire 规则从头到尾读取 Data 字节。
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        // 只要还有未解析字节就继续读取下一个字段。
        while (reader.hasNext()) {
            // tag 同时包含字段编号和 wire type。
            int tag = reader.readTag();
            // tag 右移 3 位取得字段编号。
            int field = tag >>> 3;
            // tag 低 3 位取得 wire type。
            int wire = tag & 7;
            switch (field) {
                case 1 -> {
                    // 枪是嵌套消息，因此必须使用 length-delimited 类型。
                    requireWire(field, wire, ProtoWire.LENGTH_DELIMITED);
                    // 取出当前枪的字节并递归解析，然后加入枪列表。
                    guns.add(decodeGun(reader.readBytes()));
                }
                case 2 -> {
                    // 秒级采集时间使用 varint。
                    requireWire(field, wire, ProtoWire.VARINT);
                    // int32 可能显示为负数，这里按无符号值转换成 long。
                    recordTime = Integer.toUnsignedLong(reader.readInt32());
                }
                case 3 -> {
                    // 数据类别代码也使用 varint。
                    requireWire(field, wire, ProtoWire.VARINT);
                    try {
                        // 把数字代码转换为业务枚举。
                        kind = ReportKind.fromCode(reader.readInt32());
                    } catch (IllegalArgumentException exception) {
                        // 未定义的类别转成协议异常，交给上层形成失败响应。
                        throw new ProtocolException(exception.getMessage(), exception);
                    }
                }
                // 跳过未来版本新增的未知字段，保持向前兼容。
                default -> reader.skip(wire);
            }
        }
        // 采集时间、数据类别和至少一把枪都是 103 的必填内容。
        if (recordTime == 0 || kind == null || guns.isEmpty()) {
            throw new ProtocolException("Realtime payload misses recordTime, kind, or gun list");
        }
        // 领域对象构造器还会复制列表，避免外部修改解析结果。
        return new RealtimePush(kind, recordTime, guns);
    }

    private byte[] encodeGun(GunSnapshot gun) {
        // 每把枪都是一个独立的嵌套消息。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 枪消息字段 1 保存枪地址，例如 0 代表第一把枪。
        ProtoWire.writeInt32(out, 1, gun.gunAddress());
        // 枪消息字段 2 是可重复的点位列表。
        for (PointValue point : gun.points()) {
            ProtoWire.writeBytes(out, 2, encodePoint(point));
        }
        // 返回当前枪的嵌套消息字节。
        return out.toByteArray();
    }

    private GunSnapshot decodeGun(byte[] bytes) {
        // -1 表示尚未读取到合法枪地址，因为合法地址允许从 0 开始。
        int gunAddress = -1;
        // points 收集当前枪下的所有点位。
        List<PointValue> points = new ArrayList<>();
        // 为当前枪的嵌套字节创建读取器。
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        while (reader.hasNext()) {
            // 读取并拆分当前字段的 tag。
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                // 枪地址必须是 varint。
                requireWire(field, wire, ProtoWire.VARINT);
                // 保存枪地址，后面构造 GunSnapshot。
                gunAddress = reader.readInt32();
            } else if (field == 2) {
                // 点位是嵌套消息，必须是 length-delimited。
                requireWire(field, wire, ProtoWire.LENGTH_DELIMITED);
                // 读取一个点位并追加到列表。
                points.add(decodePoint(reader.readBytes()));
            } else {
                // 忽略未知字段，避免新字段使旧网关完全无法解析。
                reader.skip(wire);
            }
        }
        // 枪地址和至少一个点位都是枪快照的必填内容。
        if (gunAddress < 0 || points.isEmpty()) {
            throw new ProtocolException("Gun payload misses address or point list");
        }
        // 返回不可变的枪快照领域对象。
        return new GunSnapshot(gunAddress, points);
    }

    private byte[] encodePoint(PointValue point) {
        // 每个点位也是一个独立嵌套消息。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 点位字段 1 保存地址，地址决定这个值代表状态、电压还是其他指标。
        ProtoWire.writeInt32(out, 1, point.pointAddress());
        // oneof 规则：根据值类型只写字段 6、7、8、9、10 中的一个。
        switch (point.type()) {
            // 字段 6 保存布尔值。
            case BOOLEAN -> ProtoWire.writeBool(out, 6, (Boolean) point.value());
            // 字段 7 保存整数值。
            case INTEGER -> ProtoWire.writeInt32(out, 7, (Integer) point.value());
            // 字段 8 保存单精度浮点数。
            case FLOAT -> ProtoWire.writeFloat(out, 8, (Float) point.value());
            // 字段 9 保存双精度浮点数。
            case DOUBLE -> ProtoWire.writeDouble(out, 9, (Double) point.value());
            // 字段 10 保存 UTF-8 字符串。
            case STRING -> ProtoWire.writeString(out, 10, (String) point.value());
        }
        // 返回当前点位的嵌套消息字节。
        return out.toByteArray();
    }

    private PointValue decodePoint(byte[] bytes) {
        // 地址 0 表示尚未读到点位地址；业务点位地址必须从 1 开始。
        int address = 0;
        // type 和 value 初始为空，用于检测是否缺值或出现多个 oneof 值。
        PointValue.Type type = null;
        Object value = null;
        // 为点位嵌套字节创建读取器。
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        while (reader.hasNext()) {
            // 读取并拆分当前字段 tag。
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                // 点位地址必须使用 varint。
                requireWire(field, wire, ProtoWire.VARINT);
                // 读取地址后继续下一轮；地址不属于 oneof 值。
                address = reader.readInt32();
                continue;
            }
            // 已经读到一种值后又出现另一个值，就违反了 oneof 规则。
            if (type != null) {
                throw new ProtocolException("Point contains more than one value");
            }
            switch (field) {
                case 6 -> {
                    // bool 的 wire type 是 varint。
                    requireWire(field, wire, ProtoWire.VARINT);
                    type = PointValue.Type.BOOLEAN;
                    value = reader.readBool();
                }
                case 7 -> {
                    // int32 的 wire type 也是 varint。
                    requireWire(field, wire, ProtoWire.VARINT);
                    type = PointValue.Type.INTEGER;
                    value = reader.readInt32();
                }
                case 8 -> {
                    // float 固定占 4 字节，因此 wire type 是 fixed32。
                    requireWire(field, wire, ProtoWire.FIXED32);
                    type = PointValue.Type.FLOAT;
                    value = reader.readFloat();
                }
                case 9 -> {
                    // double 固定占 8 字节，因此 wire type 是 fixed64。
                    requireWire(field, wire, ProtoWire.FIXED64);
                    type = PointValue.Type.DOUBLE;
                    value = reader.readDouble();
                }
                case 10 -> {
                    // string 需要先保存长度，因此是 length-delimited。
                    requireWire(field, wire, ProtoWire.LENGTH_DELIMITED);
                    type = PointValue.Type.STRING;
                    value = reader.readString();
                }
                // 未知字段按自身 wire type 跳过。
                default -> reader.skip(wire);
            }
        }
        // 一个合法点位必须同时包含正数地址和且仅一个值。
        if (address < 1 || type == null) {
            throw new ProtocolException("Point payload misses address or value");
        }
        // PointValue 构造器会再次确认 type 与 value 的 Java 类型匹配。
        return new PointValue(address, type, value);
    }

    private static void requireWire(int field, int actual, int expected) {
        // 字段编号正确但 wire type 错误也必须拒绝，否则会用错误长度读取后续字节。
        if (actual != expected) {
            throw new ProtocolException("Invalid wire type for field " + field);
        }
    }
}
