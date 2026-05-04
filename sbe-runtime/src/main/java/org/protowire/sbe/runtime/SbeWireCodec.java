package org.protowire.sbe.runtime;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.protowire.sbe.runtime.SbeConstants.*;

/**
 * Wire-level SBE marshal / unmarshal — descriptor-free. Drives the
 * encode/decode loop off a {@link MessageTemplate} (data-only), reading
 * field values from an {@link SbeFieldReader} and writing them through
 * an {@link SbeFieldWriter}.
 *
 * <p>The descriptor-driven {@code Codec} entry point in {@code :sbe}
 * supplies adapters that wrap {@code com.google.protobuf.Message} /
 * {@code Message.Builder}; a future lite-runtime tier supplies adapters
 * over typed {@code MessageLite} subclasses with codegen-emitted
 * accessors. The wire bytes produced and consumed here are byte-
 * equivalent across both tiers — that's a CI-enforced invariant via
 * {@code scripts/cross_envelope_check.sh}'s SBE rows.
 *
 * <p>Wire format follows FIX SBE: little-endian, 8-byte message header
 * carrying {@code (blockLength, templateId, schemaId, version)}, fixed-
 * size root block of {@link FieldTemplate}-laid-out fields, then
 * repeating groups appended after the root block (each group prefixed
 * with a 4-byte {@code (blockLength, count)} header).
 */
public final class SbeWireCodec {
    private SbeWireCodec() {}

    // FieldDescriptorProto.Type integers — same as PxfMeta's K_* and the
    // values protowire-go's wire codec keys off.
    private static final int K_INT64    = 3;
    private static final int K_UINT64   = 4;
    private static final int K_INT32    = 5;
    private static final int K_FIXED64  = 6;
    private static final int K_FIXED32  = 7;
    private static final int K_BOOL     = 8;
    private static final int K_STRING   = 9;
    private static final int K_BYTES    = 12;
    private static final int K_UINT32   = 13;
    private static final int K_ENUM     = 14;
    private static final int K_SFIXED32 = 15;
    private static final int K_SFIXED64 = 16;
    private static final int K_SINT32   = 17;
    private static final int K_SINT64   = 18;

    public static byte[] marshal(SbeFieldReader reader, MessageTemplate t) {
        int total = HEADER_SIZE + t.blockLength;
        for (GroupTemplate gt : t.groups) {
            int n = reader.getRepeatedCount(gt.fieldNumber);
            total += GROUP_HEADER_SIZE + n * gt.blockLength;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ORDER);

        buf.putShort(0, (short) t.blockLength);
        buf.putShort(2, (short) t.templateId);
        buf.putShort(4, (short) t.schemaId);
        buf.putShort(6, (short) t.version);

        int blockStart = HEADER_SIZE;
        for (FieldTemplate ft : t.fields) writeField(buf, blockStart, ft, reader);

        int pos = HEADER_SIZE + t.blockLength;
        for (GroupTemplate gt : t.groups) pos += marshalGroup(buf, pos, reader, gt);
        return buf.array();
    }

    static void writeField(ByteBuffer buf, int blockStart, FieldTemplate ft, SbeFieldReader reader) {
        if (ft.composite != null) {
            SbeFieldReader sub = reader.getMessageField(ft.fieldNumber);
            int subStart = blockStart + ft.offset;
            for (FieldTemplate sf : ft.composite) writeField(buf, subStart, sf, sub);
            return;
        }
        Object val = reader.getField(ft.fieldNumber);
        int off = blockStart + ft.offset;
        switch (ft.encoding) {
            case ENC_INT8 -> buf.put(off, (byte) ((Number) val).intValue());
            case ENC_INT16 -> buf.putShort(off, (short) ((Number) val).intValue());
            case ENC_INT32 -> buf.putInt(off, ((Number) val).intValue());
            case ENC_INT64 -> buf.putLong(off, ((Number) val).longValue());
            case ENC_UINT8 -> buf.put(off, (byte) uintVal(ft.kind, val));
            case ENC_UINT16 -> buf.putShort(off, (short) uintVal(ft.kind, val));
            case ENC_UINT32 -> buf.putInt(off, (int) uintVal(ft.kind, val));
            case ENC_UINT64 -> buf.putLong(off, uintVal(ft.kind, val));
            case ENC_FLOAT -> buf.putFloat(off, ((Number) val).floatValue());
            case ENC_DOUBLE -> buf.putDouble(off, ((Number) val).doubleValue());
            case ENC_CHAR -> {
                byte[] data = ft.kind == K_BYTES
                        ? toBytes(val)
                        : ((String) val).getBytes(StandardCharsets.UTF_8);
                int n = Math.min(data.length, ft.size);
                for (int i = 0; i < n; i++) buf.put(off + i, data[i]);
                for (int i = n; i < ft.size; i++) buf.put(off + i, (byte) 0);
            }
        }
    }

    static int marshalGroup(ByteBuffer buf, int pos, SbeFieldReader reader, GroupTemplate gt) {
        int n = reader.getRepeatedCount(gt.fieldNumber);
        buf.putShort(pos, (short) gt.blockLength);
        buf.putShort(pos + 2, (short) n);
        for (int i = 0; i < n; i++) {
            SbeFieldReader entry = reader.getRepeatedMessage(gt.fieldNumber, i);
            int entryStart = pos + GROUP_HEADER_SIZE + i * gt.blockLength;
            for (FieldTemplate ft : gt.fields) writeField(buf, entryStart, ft, entry);
        }
        return GROUP_HEADER_SIZE + n * gt.blockLength;
    }

    public static void unmarshal(byte[] data, SbeFieldWriter writer, MessageTemplate t) {
        if (data.length < HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for header");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ORDER);
        int blockLength = Short.toUnsignedInt(buf.getShort(0));
        int templateId = Short.toUnsignedInt(buf.getShort(2));
        if (templateId != t.templateId) {
            throw new IllegalArgumentException("sbe: template id mismatch: got " + templateId + ", want " + t.templateId);
        }
        int blockEnd = HEADER_SIZE + blockLength;
        if (data.length < blockEnd) throw new IllegalArgumentException("sbe: data too short for root block");

        for (FieldTemplate ft : t.fields) readField(buf, HEADER_SIZE, ft, writer);

        int pos = blockEnd;
        for (GroupTemplate gt : t.groups) pos += readGroup(buf, pos, gt, writer);
    }

    static void readField(ByteBuffer buf, int blockStart, FieldTemplate ft, SbeFieldWriter writer) {
        if (ft.composite != null) {
            SbeFieldWriter sub = writer.newMessageBuilder(ft.fieldNumber);
            int subStart = blockStart + ft.offset;
            for (FieldTemplate sf : ft.composite) readField(buf, subStart, sf, sub);
            sub.commit();
            return;
        }
        int off = blockStart + ft.offset;
        switch (ft.encoding) {
            case ENC_INT8 -> setInt(writer, ft, buf.get(off));
            case ENC_INT16 -> setInt(writer, ft, buf.getShort(off));
            case ENC_INT32 -> setInt(writer, ft, buf.getInt(off));
            case ENC_INT64 -> setInt(writer, ft, buf.getLong(off));
            case ENC_UINT8 -> setUint(writer, ft, buf.get(off) & 0xFFL);
            case ENC_UINT16 -> setUint(writer, ft, buf.getShort(off) & 0xFFFFL);
            case ENC_UINT32 -> setUint(writer, ft, buf.getInt(off) & 0xFFFFFFFFL);
            case ENC_UINT64 -> setUint(writer, ft, buf.getLong(off));
            case ENC_FLOAT -> setFloat(writer, ft, buf.getFloat(off));
            case ENC_DOUBLE -> setFloat(writer, ft, buf.getDouble(off));
            case ENC_CHAR -> {
                byte[] raw = new byte[ft.size];
                for (int i = 0; i < ft.size; i++) raw[i] = buf.get(off + i);
                if (ft.kind == K_BYTES) {
                    writer.setField(ft.fieldNumber, ByteString.copyFrom(raw));
                } else {
                    int n = ft.size;
                    while (n > 0 && raw[n - 1] == 0) n--;
                    writer.setField(ft.fieldNumber, new String(raw, 0, n, StandardCharsets.UTF_8));
                }
            }
        }
    }

    static int readGroup(ByteBuffer buf, int pos, GroupTemplate gt, SbeFieldWriter writer) {
        if (buf.capacity() < pos + GROUP_HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for group header");
        int blockLength = Short.toUnsignedInt(buf.getShort(pos));
        int count = Short.toUnsignedInt(buf.getShort(pos + 2));
        int total = GROUP_HEADER_SIZE + count * blockLength;
        for (int i = 0; i < count; i++) {
            int entryStart = pos + GROUP_HEADER_SIZE + i * blockLength;
            SbeFieldWriter entry = writer.newGroupEntry(gt.fieldNumber);
            for (FieldTemplate ft : gt.fields) readField(buf, entryStart, ft, entry);
            entry.commit();
        }
        return total;
    }

    static void setInt(SbeFieldWriter writer, FieldTemplate ft, long v) {
        switch (ft.kind) {
            case K_INT32, K_SINT32, K_SFIXED32 -> writer.setField(ft.fieldNumber, (int) v);
            case K_INT64, K_SINT64, K_SFIXED64 -> writer.setField(ft.fieldNumber, v);
            default -> throw new IllegalStateException("sbe: bad target for int kind: " + ft.kind);
        }
    }

    static void setUint(SbeFieldWriter writer, FieldTemplate ft, long v) {
        switch (ft.kind) {
            case K_BOOL -> writer.setField(ft.fieldNumber, v != 0);
            case K_ENUM -> writer.setField(ft.fieldNumber, (int) v);
            case K_INT32, K_UINT32, K_SINT32, K_FIXED32, K_SFIXED32 -> writer.setField(ft.fieldNumber, (int) v);
            case K_INT64, K_UINT64, K_SINT64, K_FIXED64, K_SFIXED64 -> writer.setField(ft.fieldNumber, v);
            default -> throw new IllegalStateException("sbe: bad target for uint kind: " + ft.kind);
        }
    }

    static void setFloat(SbeFieldWriter writer, FieldTemplate ft, double v) {
        switch (ft.kind) {
            case 2 /* FLOAT */ -> writer.setField(ft.fieldNumber, (float) v);
            case 1 /* DOUBLE */ -> writer.setField(ft.fieldNumber, v);
            default -> throw new IllegalStateException("sbe: bad target for float kind: " + ft.kind);
        }
    }

    /**
     * Encode-side coercion of an unsigned integer wire value from whatever
     * Java type the reader hands us (Boolean for BOOL, Integer for ENUM /
     * INT32-family, Long for INT64-family).
     */
    static long uintVal(int kind, Object val) {
        return switch (kind) {
            case K_BOOL -> ((Boolean) val) ? 1L : 0L;
            case K_ENUM -> ((Integer) val).longValue();
            case K_INT32, K_UINT32, K_SINT32, K_FIXED32, K_SFIXED32 -> Integer.toUnsignedLong((Integer) val);
            case K_INT64, K_UINT64, K_SINT64, K_FIXED64, K_SFIXED64 -> (Long) val;
            default -> ((Number) val).longValue();
        };
    }

    /** Encode-side coercion for ENC_CHAR over a BYTES-kind field — accepts ByteString or byte[]. */
    static byte[] toBytes(Object val) {
        if (val instanceof ByteString bs) return bs.toByteArray();
        if (val instanceof byte[] b) return b;
        throw new IllegalArgumentException("sbe: BYTES value must be ByteString or byte[], got "
            + val.getClass().getName());
    }
}
