// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.runtime;

import org.protowire.sbe.FieldTemplate;
import org.protowire.sbe.GroupTemplate;
import org.protowire.sbe.MessageTemplate;

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
            // numInGroup is a uint16: a larger count would be written as its
            // low 16 bits, and a reader would take the entries past them for
            // trailing bytes (protowire-go#250).
            if (n > SbeKinds.MAX_UINT16) {
                throw new IllegalArgumentException("sbe: field " + gt.fullName + " has " + n + " entries, past " + SbeKinds.MAX_UINT16
                        + ", the largest numInGroup SBE can state");
            }
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
            case ENC_INT8, ENC_INT16, ENC_INT32, ENC_INT64, ENC_UINT8, ENC_UINT16, ENC_UINT32, ENC_UINT64 -> {
                // The value is read by the field's kind, and written as the
                // low bytes of its two's complement once it is known to fit
                // the primitive (protowire-go#252).
                boolean unsigned = SbeKinds.unsignedKind(ft.kind);
                long v = intValue(ft.kind, val);
                long[] enc = SbeKinds.encRange(ft.encoding, ft.size);
                long[] field = SbeKinds.kindRange(ft.kind);
                boolean check = field[0] < enc[0] || Long.compareUnsigned(field[1], enc[1]) > 0;
                if (check && !SbeKinds.inRange(v, unsigned, enc[0], enc[1])) {
                    throw new IllegalArgumentException("sbe: field " + ft.fullName + ": value " + SbeKinds.intString(v, unsigned)
                            + " is outside the range of " + ft.encoding + ", [" + enc[0] + ", " + Long.toUnsignedString(enc[1]) + "]");
                }
                switch (ft.size) {
                    case 1 -> buf.put(off, (byte) v);
                    case 2 -> buf.putShort(off, (short) v);
                    case 4 -> buf.putInt(off, (int) v);
                    default -> buf.putLong(off, v);
                }
            }
            case ENC_FLOAT -> {
                double f = ((Number) val).doubleValue();
                if (ft.kind == SbeKinds.K_DOUBLE && !SbeKinds.floatFits(f)) {
                    throw new IllegalArgumentException("sbe: field " + ft.fullName + ": value " + SbeKinds.goG(f)
                            + " is outside the range of float, ±" + SbeKinds.goG(Float.MAX_VALUE));
                }
                buf.putFloat(off, (float) f);
            }
            case ENC_DOUBLE -> buf.putDouble(off, ((Number) val).doubleValue());
            case ENC_CHAR -> {
                // An overlong value is cut: bytes at the limit, a string at
                // the last whole character at or before it. The bytes left
                // over are NUL either way (draft -01 § SBE Annotations).
                byte[] data;
                int n;
                if (ft.kind == K_BYTES) {
                    data = toBytes(val);
                    n = Math.min(data.length, ft.size);
                } else {
                    data = ((String) val).getBytes(StandardCharsets.UTF_8);
                    n = SbeUtf8.cutLength(data, ft.size);
                }
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
        unmarshal(data, writer, t, MAX_MESSAGE_SIZE, MAX_REPEATED_COUNT);
    }

    /**
     * As {@link #unmarshal(byte[], SbeFieldWriter, MessageTemplate)} under
     * per-call limits (HARDENING.md § Mandatory limits, #79):
     * {@code maxMessageSize} is checked before the header is read,
     * {@code maxRepeatedCount} against each group's wire-declared
     * {@code numInGroup} before any entry is allocated.
     */
    public static void unmarshal(byte[] data, SbeFieldWriter writer, MessageTemplate t,
                                 int maxMessageSize, int maxRepeatedCount) {
        if (data.length > maxMessageSize) {
            throw new IllegalArgumentException("sbe: input of " + data.length + " bytes exceeds MaxMessageSize=" + maxMessageSize);
        }
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("sbe: data too short for header: " + data.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ORDER);
        int blockLength = Short.toUnsignedInt(buf.getShort(0));
        int templateId = Short.toUnsignedInt(buf.getShort(2));
        int schemaId = Short.toUnsignedInt(buf.getShort(4));
        if (templateId != t.templateId) {
            throw new IllegalArgumentException("sbe: template ID mismatch: got " + templateId + ", want " + t.templateId);
        }
        // A template id is unique only within a schema, so it alone does not
        // say the bytes are this message's: they may be another schema's
        // message with the same id (protowire-go#257; draft -01 § Header and
        // Block Length, protowire#571).
        if (schemaId != t.schemaId) {
            throw new IllegalArgumentException("sbe: schema ID mismatch: got " + schemaId + ", want " + t.schemaId
                    + " for template " + t.templateId);
        }
        // HARDENING.md § SBE step 2: a wire blockLength below the template's
        // would have field reads slice past the block. Schema evolution may
        // push it higher, which is fine.
        if (blockLength < t.blockLength) {
            throw new IllegalArgumentException("sbe: wire blockLength " + blockLength + " < schema blockLength " + t.blockLength
                    + " for template " + t.templateId);
        }
        int blockEnd = HEADER_SIZE + blockLength;
        if (data.length < blockEnd) {
            throw new IllegalArgumentException("sbe: data too short for root block: need " + blockEnd + ", have " + data.length);
        }

        for (FieldTemplate ft : t.fields) readField(buf, HEADER_SIZE, ft, writer);

        int pos = blockEnd;
        for (GroupTemplate gt : t.groups) pos += readGroup(buf, pos, gt, writer, maxRepeatedCount);
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
            case ENC_INT8, ENC_INT16, ENC_INT32, ENC_INT64, ENC_UINT8, ENC_UINT16, ENC_UINT32, ENC_UINT64 -> {
                // Sign- or zero-extended per the primitive (draft -01 § SBE
                // Annotations), then stored by the field's kind; a value the
                // field cannot hold is refused rather than cut to its low
                // bits (protowire-go#252).
                boolean signed = ft.encoding.charAt(0) == 'i';
                long v = switch (ft.size) {
                    case 1 -> signed ? buf.get(off) : buf.get(off) & 0xFFL;
                    case 2 -> signed ? buf.getShort(off) : buf.getShort(off) & 0xFFFFL;
                    case 4 -> signed ? buf.getInt(off) : buf.getInt(off) & 0xFFFFFFFFL;
                    default -> buf.getLong(off);
                };
                long[] enc = SbeKinds.encRange(ft.encoding, ft.size);
                long[] field = SbeKinds.kindRange(ft.kind);
                // A bool reads any nonzero primitive as true, as it always has.
                boolean check = ft.kind != SbeKinds.K_BOOL && (enc[0] < field[0] || Long.compareUnsigned(enc[1], field[1]) > 0);
                if (check && !SbeKinds.inRange(v, !signed, field[0], field[1])) {
                    throw new IllegalArgumentException("sbe: field " + ft.fullName + ": wire value " + SbeKinds.intString(v, !signed)
                            + " (" + ft.encoding + ") does not fit a " + SbeKinds.kindName(ft.kind) + " field, [" + field[0] + ", "
                            + Long.toUnsignedString(field[1]) + "]");
                }
                setInteger(writer, ft, v);
            }
            case ENC_FLOAT -> setFloat(writer, ft, buf.getFloat(off));
            case ENC_DOUBLE -> {
                double f = buf.getDouble(off);
                if (ft.kind == SbeKinds.K_FLOAT && !SbeKinds.floatFits(f)) {
                    throw new IllegalArgumentException("sbe: field " + ft.fullName + ": wire value " + SbeKinds.goG(f)
                            + " does not fit a float field, ±" + SbeKinds.goG(Float.MAX_VALUE));
                }
                setFloat(writer, ft, f);
            }
            case ENC_CHAR -> {
                byte[] raw = new byte[ft.size];
                for (int i = 0; i < ft.size; i++) raw[i] = buf.get(off + i);
                if (ft.kind == K_BYTES) {
                    writer.setField(ft.fieldNumber, ByteString.copyFrom(raw));
                } else {
                    int n = ft.size;
                    while (n > 0 && raw[n - 1] == 0) n--;
                    writer.setField(ft.fieldNumber, SbeUtf8.decode(raw, 0, n, ft.name));
                }
            }
        }
    }

    static int readGroup(ByteBuffer buf, int pos, GroupTemplate gt, SbeFieldWriter writer, int maxRepeatedCount) {
        if (buf.capacity() < pos + GROUP_HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for group header");
        int blockLength = Short.toUnsignedInt(buf.getShort(pos));
        int count = Short.toUnsignedInt(buf.getShort(pos + 2));
        // HARDENING.md § SBE steps 3-4: an entry block below the template's
        // would have field reads slice past the entry (and a zero one with
        // a non-zero count is unbounded allocation for no bytes); the
        // declared count is bounded against the bytes actually present
        // without forming the product, which can overflow int
        // (0xFFFF × 0xFFFF); and against MaxRepeatedCount before any entry
        // is allocated (#79).
        if (blockLength < gt.blockLength) {
            throw new IllegalArgumentException("sbe: group " + gt.name + " wire blockLength " + blockLength
                    + " < schema blockLength " + gt.blockLength);
        }
        int remaining = buf.capacity() - pos - GROUP_HEADER_SIZE;
        if (blockLength > 0 && count > remaining / blockLength) {
            throw new IllegalArgumentException("sbe: group " + gt.name + " declares " + count + " entries × "
                    + blockLength + " bytes, " + remaining + " bytes remaining");
        }
        if (count > maxRepeatedCount) {
            throw new IllegalArgumentException("sbe: group " + gt.name + " declares " + count
                    + " entries, MaxRepeatedCount=" + maxRepeatedCount);
        }
        int total = GROUP_HEADER_SIZE + count * blockLength;
        for (int i = 0; i < count; i++) {
            int entryStart = pos + GROUP_HEADER_SIZE + i * blockLength;
            SbeFieldWriter entry = writer.newGroupEntry(gt.fieldNumber);
            for (FieldTemplate ft : gt.fields) readField(buf, entryStart, ft, entry);
            entry.commit();
        }
        return total;
    }

    /** An integer already checked to fit, stored as the field's kind holds it: a bool, an int or a long. */
    static void setInteger(SbeFieldWriter writer, FieldTemplate ft, long v) {
        switch (ft.kind) {
            case K_BOOL -> writer.setField(ft.fieldNumber, v != 0);
            case K_ENUM, K_INT32, K_UINT32, K_SINT32, K_FIXED32, K_SFIXED32 -> writer.setField(ft.fieldNumber, (int) v);
            case K_INT64, K_UINT64, K_SINT64, K_FIXED64, K_SFIXED64 -> writer.setField(ft.fieldNumber, v);
            default -> throw new IllegalStateException("sbe: bad target for integer kind: " + ft.kind);
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
     * An integer field's value as the codec compares it: unsigned for a bool,
     * uint32 or uint64 (see {@link SbeKinds#unsignedKind}), an int64's bits
     * otherwise, from the Java type the reader hands over (Boolean for BOOL,
     * Integer for ENUM and the 32-bit kinds, Long for the 64-bit ones).
     */
    static long intValue(int kind, Object val) {
        return switch (kind) {
            case K_BOOL -> ((Boolean) val) ? 1L : 0L;
            case K_UINT32, K_FIXED32 -> Integer.toUnsignedLong(((Number) val).intValue());
            case K_ENUM, K_INT32, K_SINT32, K_SFIXED32 -> ((Number) val).intValue();
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
