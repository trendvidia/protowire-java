package com.trendvidia.protowire.sbe;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.trendvidia.protowire.sbe.Codec.*;

final class SbeMarshal {
    private SbeMarshal() {}

    static byte[] marshal(Message msg, MessageTemplate t) {
        int total = HEADER_SIZE + t.blockLength;
        for (GroupTemplate gt : t.groups) {
            int n = msg.getRepeatedFieldCount(gt.fd);
            total += GROUP_HEADER_SIZE + n * gt.blockLength;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ORDER);

        buf.putShort(0, (short) t.blockLength);
        buf.putShort(2, (short) t.templateId);
        buf.putShort(4, (short) t.schemaId);
        buf.putShort(6, (short) t.version);

        int blockStart = HEADER_SIZE;
        for (FieldTemplate ft : t.fields) writeField(buf, blockStart, ft, msg);

        int pos = HEADER_SIZE + t.blockLength;
        for (GroupTemplate gt : t.groups) pos += marshalGroup(buf, pos, msg, gt);
        return buf.array();
    }

    static void writeField(ByteBuffer buf, int blockStart, FieldTemplate ft, Message msg) {
        if (ft.composite != null) {
            Message sub = (Message) msg.getField(ft.fd);
            int subStart = blockStart + ft.offset;
            for (FieldTemplate sf : ft.composite) writeField(buf, subStart, sf, sub);
            return;
        }
        Object val = msg.getField(ft.fd);
        int off = blockStart + ft.offset;
        switch (ft.encoding) {
            case ENC_INT8 -> buf.put(off, (byte) ((Number) val).intValue());
            case ENC_INT16 -> buf.putShort(off, (short) ((Number) val).intValue());
            case ENC_INT32 -> buf.putInt(off, ((Number) val).intValue());
            case ENC_INT64 -> buf.putLong(off, ((Number) val).longValue());
            case ENC_UINT8 -> buf.put(off, (byte) uintVal(ft.fd, val));
            case ENC_UINT16 -> buf.putShort(off, (short) uintVal(ft.fd, val));
            case ENC_UINT32 -> buf.putInt(off, (int) uintVal(ft.fd, val));
            case ENC_UINT64 -> buf.putLong(off, uintVal(ft.fd, val));
            case ENC_FLOAT -> buf.putFloat(off, ((Number) val).floatValue());
            case ENC_DOUBLE -> buf.putDouble(off, ((Number) val).doubleValue());
            case ENC_CHAR -> {
                byte[] data = ft.fd.getJavaType() == FieldDescriptor.JavaType.BYTE_STRING
                        ? ((ByteString) val).toByteArray()
                        : ((String) val).getBytes(StandardCharsets.UTF_8);
                int n = Math.min(data.length, ft.size);
                for (int i = 0; i < n; i++) buf.put(off + i, data[i]);
                for (int i = n; i < ft.size; i++) buf.put(off + i, (byte) 0);
            }
        }
    }

    static int marshalGroup(ByteBuffer buf, int pos, Message msg, GroupTemplate gt) {
        int n = msg.getRepeatedFieldCount(gt.fd);
        buf.putShort(pos, (short) gt.blockLength);
        buf.putShort(pos + 2, (short) n);
        for (int i = 0; i < n; i++) {
            Message entry = (Message) msg.getRepeatedField(gt.fd, i);
            int entryStart = pos + GROUP_HEADER_SIZE + i * gt.blockLength;
            for (FieldTemplate ft : gt.fields) writeField(buf, entryStart, ft, entry);
        }
        return GROUP_HEADER_SIZE + n * gt.blockLength;
    }

    static long uintVal(FieldDescriptor fd, Object val) {
        return switch (fd.getJavaType()) {
            case BOOLEAN -> ((Boolean) val) ? 1 : 0;
            case ENUM -> ((EnumValueDescriptor) val).getNumber();
            case INT -> Integer.toUnsignedLong((Integer) val);
            case LONG -> (Long) val;
            default -> ((Number) val).longValue();
        };
    }
}
