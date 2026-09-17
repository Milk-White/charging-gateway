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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (GunSnapshot gun : push.guns()) {
            ProtoWire.writeBytes(out, 1, encodeGun(gun));
        }
        ProtoWire.writeInt32(out, 2, Math.toIntExact(push.recordTime()));
        ProtoWire.writeInt32(out, 3, push.kind().code());
        return out.toByteArray();
    }

    public RealtimePush decode(byte[] bytes) {
        List<GunSnapshot> guns = new ArrayList<>();
        long recordTime = 0;
        ReportKind kind = null;
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> {
                    requireWire(field, wire, ProtoWire.LENGTH_DELIMITED);
                    guns.add(decodeGun(reader.readBytes()));
                }
                case 2 -> {
                    requireWire(field, wire, ProtoWire.VARINT);
                    recordTime = Integer.toUnsignedLong(reader.readInt32());
                }
                case 3 -> {
                    requireWire(field, wire, ProtoWire.VARINT);
                    try {
                        kind = ReportKind.fromCode(reader.readInt32());
                    } catch (IllegalArgumentException exception) {
                        throw new ProtocolException(exception.getMessage(), exception);
                    }
                }
                default -> reader.skip(wire);
            }
        }
        if (recordTime == 0 || kind == null || guns.isEmpty()) {
            throw new ProtocolException("Realtime payload misses recordTime, kind, or gun list");
        }
        return new RealtimePush(kind, recordTime, guns);
    }

    private byte[] encodeGun(GunSnapshot gun) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtoWire.writeInt32(out, 1, gun.gunAddress());
        for (PointValue point : gun.points()) {
            ProtoWire.writeBytes(out, 2, encodePoint(point));
        }
        return out.toByteArray();
    }

    private GunSnapshot decodeGun(byte[] bytes) {
        int gunAddress = -1;
        List<PointValue> points = new ArrayList<>();
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                requireWire(field, wire, ProtoWire.VARINT);
                gunAddress = reader.readInt32();
            } else if (field == 2) {
                requireWire(field, wire, ProtoWire.LENGTH_DELIMITED);
                points.add(decodePoint(reader.readBytes()));
            } else {
                reader.skip(wire);
            }
        }
        if (gunAddress < 0 || points.isEmpty()) {
            throw new ProtocolException("Gun payload misses address or point list");
        }
        return new GunSnapshot(gunAddress, points);
    }

    private byte[] encodePoint(PointValue point) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtoWire.writeInt32(out, 1, point.pointAddress());
        switch (point.type()) {
            case BOOLEAN -> ProtoWire.writeBool(out, 6, (Boolean) point.value());
            case INTEGER -> ProtoWire.writeInt32(out, 7, (Integer) point.value());
            case FLOAT -> ProtoWire.writeFloat(out, 8, (Float) point.value());
            case DOUBLE -> ProtoWire.writeDouble(out, 9, (Double) point.value());
            case STRING -> ProtoWire.writeString(out, 10, (String) point.value());
        }
        return out.toByteArray();
    }

    private PointValue decodePoint(byte[] bytes) {
        int address = 0;
        PointValue.Type type = null;
        Object value = null;
        ProtoWire.Reader reader = new ProtoWire.Reader(bytes);
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1) {
                requireWire(field, wire, ProtoWire.VARINT);
                address = reader.readInt32();
                continue;
            }
            if (type != null) {
                throw new ProtocolException("Point contains more than one value");
            }
            switch (field) {
                case 6 -> { requireWire(field, wire, ProtoWire.VARINT); type = PointValue.Type.BOOLEAN; value = reader.readBool(); }
                case 7 -> { requireWire(field, wire, ProtoWire.VARINT); type = PointValue.Type.INTEGER; value = reader.readInt32(); }
                case 8 -> { requireWire(field, wire, ProtoWire.FIXED32); type = PointValue.Type.FLOAT; value = reader.readFloat(); }
                case 9 -> { requireWire(field, wire, ProtoWire.FIXED64); type = PointValue.Type.DOUBLE; value = reader.readDouble(); }
                case 10 -> { requireWire(field, wire, ProtoWire.LENGTH_DELIMITED); type = PointValue.Type.STRING; value = reader.readString(); }
                default -> reader.skip(wire);
            }
        }
        if (address < 1 || type == null) {
            throw new ProtocolException("Point payload misses address or value");
        }
        return new PointValue(address, type, value);
    }

    private static void requireWire(int field, int actual, int expected) {
        if (actual != expected) {
            throw new ProtocolException("Invalid wire type for field " + field);
        }
    }
}
