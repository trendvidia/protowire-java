package org.protowire.sbe;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.protowire.sbe.Codec.*;

final class SbeUnmarshal {
    private SbeUnmarshal() {}

    static void unmarshal(byte[] data, Message.Builder b, MessageTemplate t) {
        if (data.length < HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for header");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ORDER);
        int blockLength = Short.toUnsignedInt(buf.getShort(0));
        int templateId = Short.toUnsignedInt(buf.getShort(2));
        if (templateId != t.templateId) {
            throw new IllegalArgumentException("sbe: template id mismatch: got " + templateId + ", want " + t.templateId);
        }
        int blockEnd = HEADER_SIZE + blockLength;
        if (data.length < blockEnd) throw new IllegalArgumentException("sbe: data too short for root block");

        for (FieldTemplate ft : t.fields) readField(buf, HEADER_SIZE, ft, b);

        int pos = blockEnd;
        for (GroupTemplate gt : t.groups) pos += readGroup(buf, pos, gt, b);
    }

    static void readField(ByteBuffer buf, int blockStart, FieldTemplate ft, Message.Builder b) {
        if (ft.composite != null) {
            Message.Builder sub = b.newBuilderForField(ft.fd);
            int subStart = blockStart + ft.offset;
            for (FieldTemplate sf : ft.composite) readField(buf, subStart, sf, sub);
            b.setField(ft.fd, sub.build());
            return;
        }
        int off = blockStart + ft.offset;
        switch (ft.encoding) {
            case ENC_INT8 -> setInt(b, ft.fd, buf.get(off));
            case ENC_INT16 -> setInt(b, ft.fd, buf.getShort(off));
            case ENC_INT32 -> setInt(b, ft.fd, buf.getInt(off));
            case ENC_INT64 -> setInt(b, ft.fd, buf.getLong(off));
            case ENC_UINT8 -> setUint(b, ft.fd, buf.get(off) & 0xFFL);
            case ENC_UINT16 -> setUint(b, ft.fd, buf.getShort(off) & 0xFFFFL);
            case ENC_UINT32 -> setUint(b, ft.fd, buf.getInt(off) & 0xFFFFFFFFL);
            case ENC_UINT64 -> setUint(b, ft.fd, buf.getLong(off));
            case ENC_FLOAT -> setFloat(b, ft.fd, buf.getFloat(off));
            case ENC_DOUBLE -> setFloat(b, ft.fd, buf.getDouble(off));
            case ENC_CHAR -> {
                byte[] raw = new byte[ft.size];
                for (int i = 0; i < ft.size; i++) raw[i] = buf.get(off + i);
                if (ft.fd.getJavaType() == FieldDescriptor.JavaType.BYTE_STRING) {
                    b.setField(ft.fd, ByteString.copyFrom(raw));
                } else {
                    int n = ft.size;
                    while (n > 0 && raw[n - 1] == 0) n--;
                    b.setField(ft.fd, new String(raw, 0, n, StandardCharsets.UTF_8));
                }
            }
        }
    }

    static int readGroup(ByteBuffer buf, int pos, GroupTemplate gt, Message.Builder b) {
        if (buf.capacity() < pos + GROUP_HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for group header");
        int blockLength = Short.toUnsignedInt(buf.getShort(pos));
        int count = Short.toUnsignedInt(buf.getShort(pos + 2));
        int total = GROUP_HEADER_SIZE + count * blockLength;
        for (int i = 0; i < count; i++) {
            int entryStart = pos + GROUP_HEADER_SIZE + i * blockLength;
            Message.Builder entry = b.newBuilderForField(gt.fd);
            for (FieldTemplate ft : gt.fields) readField(buf, entryStart, ft, entry);
            b.addRepeatedField(gt.fd, entry.build());
        }
        return total;
    }

    static void setInt(Message.Builder b, FieldDescriptor fd, long v) {
        switch (fd.getJavaType()) {
            case INT -> b.setField(fd, (int) v);
            case LONG -> b.setField(fd, v);
            default -> throw new IllegalStateException("sbe: bad target for int: " + fd.getJavaType());
        }
    }

    static void setUint(Message.Builder b, FieldDescriptor fd, long v) {
        switch (fd.getJavaType()) {
            case BOOLEAN -> b.setField(fd, v != 0);
            case ENUM -> {
                EnumValueDescriptor ev = fd.getEnumType().findValueByNumber((int) v);
                if (ev == null) ev = fd.getEnumType().findValueByNumberCreatingIfUnknown((int) v);
                b.setField(fd, ev);
            }
            case INT -> b.setField(fd, (int) v);
            case LONG -> b.setField(fd, v);
            default -> throw new IllegalStateException("sbe: bad target for uint: " + fd.getJavaType());
        }
    }

    static void setFloat(Message.Builder b, FieldDescriptor fd, double v) {
        switch (fd.getJavaType()) {
            case FLOAT -> b.setField(fd, (float) v);
            case DOUBLE -> b.setField(fd, v);
            default -> throw new IllegalStateException("sbe: bad target for float: " + fd.getJavaType());
        }
    }
}
