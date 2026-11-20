// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;
import org.protowire.pxf.BigFloats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema-free protobuf binary marshaling for plain Java classes.
 *
 * <p>Field numbers come from the {@link ProtoField} annotation. Encoding follows proto3 semantics:
 * zero-value singular fields are omitted. The wire format is standard protobuf binary, byte for
 * byte what protowire-go's {@code encoding/pb} writes for the same struct (STABILITY.md promise
 * 2; #77):
 *
 * <ul>
 *   <li>Signed integers are proto3 {@code int32} / {@code int64} — a plain varint, sign-extended
 *       to ten bytes for a negative value. {@link ProtoField#zigzag()} opts a field into
 *       {@code sint32} / {@code sint64} instead, like Go's {@code zigzag} tag option.</li>
 *   <li>A {@link List} of numeric or boolean elements is encoded packed, one length-delimited
 *       record carrying every element, zeros included; other element types are one record per
 *       element. Both packed and unpacked input decode.</li>
 *   <li>A map entry always carries its {@code key} and its {@code value}, zero-valued or not — the
 *       layout protoc, protobuf-go and C++ protobuf write (protowire#295; #78). An entry lacking
 *       either still decodes, to the zero value.</li>
 *   <li>A field is read only from a tag of the wire type its type is written with, as protobuf
 *       reads it (protowire#614; #257). A known field whose tag carries another wire type is
 *       skipped as an unknown field and left as it was. Wire types 6 and 7 are refused on any
 *       field, and so is a skipped value cut short.</li>
 * </ul>
 *
 * <p>Supported types: {@code boolean}, all integer primitives + boxed, {@code float}, {@code double},
 * {@link String}, {@code byte[]}, {@link BigInteger}, {@link BigDecimal}, nested classes, {@link List}
 * of any of the above, {@link Map} with scalar keys.
 */
public final class Pb {

    private Pb() {}

    public static byte[] marshal(Object obj) throws IOException {
        return marshal(obj, MarshalOptions.defaults());
    }

    /**
     * As {@link #marshal(Object)} under per-call options: a value nested more
     * deeply than {@link MarshalOptions#maxNestingDepth()} is refused (#167).
     */
    public static byte[] marshal(Object obj, MarshalOptions opts) throws IOException {
        if (obj == null) throw new IllegalArgumentException("pb.marshal: object is null");
        return marshalNested(obj, 0, opts.maxNestingDepth());
    }

    /** A struct's bytes at submessage depth {@code depth}, counted as a decoder counts it. */
    private static byte[] marshalNested(Object obj, int depth, int maxDepth) throws IOException {
        if (depth > maxDepth) {
            throw new IOException("nesting depth exceeds MaxNestingDepth=" + maxDepth);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        marshalStruct(out, obj, depth, maxDepth);
        out.flush();
        return baos.toByteArray();
    }

    public static <T> T unmarshal(byte[] data, Class<T> cls) throws IOException {
        return unmarshal(data, cls, UnmarshalOptions.defaults());
    }

    /** As {@link #unmarshal(byte[], Class)} under per-call limits (#79). */
    public static <T> T unmarshal(byte[] data, Class<T> cls, UnmarshalOptions opts) throws IOException {
        try {
            T obj = cls.getDeclaredConstructor().newInstance();
            unmarshal(data, obj, opts);
            return obj;
        } catch (ReflectiveOperationException e) {
            throw new IOException("pb.unmarshal: cannot instantiate " + cls.getName(), e);
        }
    }

    /**
     * HARDENING.md {@code MaxNestingDepth}: the deepest submessage / map-entry
     * nesting {@link #unmarshal} follows, counted as descents from a root at
     * depth 0 — every submessage and every map entry is one descent
     * (HARDENING.md § Recursion, protowire#301). 100 descents are accepted;
     * 101 are rejected. The counter is carried into every nested
     * {@code CodedInputStream} rather than reset with it.
     */
    public static final int MAX_NESTING_DEPTH = 100;

    /**
     * HARDENING.md {@code MaxNumericLiteralDigits}, bounding the magnitude of
     * a {@code pxf.Decimal}'s {@code scale} on the wire: a Decimal is
     * unscaled × 10^(-scale), so a consumer that renders it materialises
     * 10^|scale| — work and memory proportional to a value the input sets in
     * five bytes. A scale is a digit count, which is why the draft's
     * numeric-literal digit cap is the bound rather than a new one: a
     * Decimal with scale 4096 is the wire form of a 4096-digit literal.
     * Same value as {@code org.protowire.pxf.Limits.MAX_NUMERIC_LITERAL_DIGITS};
     * copied because this module has no dependency on {@code :pxf-runtime}.
     */
    public static final int MAX_NUMERIC_LITERAL_DIGITS = 4096;

    /**
     * {@code MaxBigFloatExponent}: the magnitude of a {@code pxf.BigFloat}
     * value's binary exponent, the wire {@code exponent} plus the mantissa's
     * bit length (protowire#281). Same value as
     * {@code org.protowire.pxf.Limits.MAX_BIG_FLOAT_EXPONENT}.
     */
    public static final int MAX_BIG_FLOAT_EXPONENT = 16384;

    /**
     * HARDENING.md {@code MaxMessageSize}: the total input to one decode
     * call, 64 MiB, checked before the first byte is read (#79).
     */
    public static final int MAX_MESSAGE_SIZE = 64 << 20;

    /**
     * HARDENING.md {@code MaxRepeatedCount}: the element count of any
     * repeated or map field, checked before the (n+1)th element is added.
     * Equal to {@code MaxMessageSize}.
     */
    public static final int MAX_REPEATED_COUNT = MAX_MESSAGE_SIZE;

    public static void unmarshal(byte[] data, Object dest) throws IOException {
        unmarshal(data, dest, UnmarshalOptions.defaults());
    }

    /** As {@link #unmarshal(byte[], Object)} under per-call limits (#79). */
    public static void unmarshal(byte[] data, Object dest, UnmarshalOptions opts) throws IOException {
        if (data.length > opts.maxMessageSize()) {
            throw new IOException("input of " + data.length + " bytes exceeds MaxMessageSize=" + opts.maxMessageSize());
        }
        CodedInputStream in = CodedInputStream.newInstance(data);
        unmarshalStruct(in, dest, 0, opts);
        // The Validator runs on the bound object, as protowire-go's (#205).
        org.protowire.check.Check.validate(opts.validator(), dest);
    }

    /**
     * One level of descent into a length-delimited submessage. {@code depth}
     * is the submessage's own depth (top-level struct = 0), exactly as in
     * protowire-go's {@code unmarshalStruct}: the counter is checked here,
     * on the inner decoder's entry, so a fresh {@code CodedInputStream}
     * cannot reset it.
     */
    private static Object unmarshalNested(byte[] data, Class<?> type, int depth, UnmarshalOptions opts) throws IOException {
        if (depth > opts.maxNestingDepth()) {
            throw new IOException("nesting depth exceeds MaxNestingDepth=" + opts.maxNestingDepth());
        }
        try {
            Object obj = type.getDeclaredConstructor().newInstance();
            unmarshalStruct(CodedInputStream.newInstance(data), obj, depth, opts);
            return obj;
        } catch (ReflectiveOperationException e) {
            throw new IOException("pb.unmarshal: cannot instantiate " + type.getName(), e);
        }
    }

    // -- struct info cache ---------------------------------------------------

    /**
     * One {@link ProtoField}. {@code wireType} is the wire type the field's
     * value is written with, and {@code packed} says a list also reads the
     * packed (length-delimited) form: the wire types it is read from.
     */
    private record FieldInfo(Field field, int number, FieldKind kind, Class<?> elementClass, Class<?> mapKeyClass,
                             boolean zigzag, int wireType, boolean packed) {

        /** Whether a tag of this wire type carries the field's value; any other is skipped as unknown. */
        boolean accepts(int wire) {
            return wire == wireType || (packed && wire == WireFormat.WIRETYPE_LENGTH_DELIMITED);
        }
    }

    private record StructInfo(List<FieldInfo> ordered, Map<Integer, FieldInfo> byNumber) {}

    private static final Map<Class<?>, StructInfo> CACHE = new ConcurrentHashMap<>();

    private static StructInfo info(Class<?> cls) {
        return CACHE.computeIfAbsent(cls, Pb::buildInfo);
    }

    private static StructInfo buildInfo(Class<?> cls) {
        List<FieldInfo> ordered = new ArrayList<>();
        Map<Integer, FieldInfo> byNumber = new HashMap<>();
        for (Field f : cls.getDeclaredFields()) {
            ProtoField pf = f.getAnnotation(ProtoField.class);
            if (pf == null) continue;
            f.setAccessible(true);
            FieldKind kind = FieldKind.classify(f);
            Class<?> elem = elementClass(f, kind);
            Class<?> mapKey = kind == FieldKind.MAP ? mapKeyClass(f) : null;
            int wire = kind == FieldKind.MAP ? WireFormat.WIRETYPE_LENGTH_DELIMITED : wireTypeOf(elem);
            boolean packed = kind == FieldKind.LIST && isPackable(elem);
            FieldInfo fi = new FieldInfo(f, pf.value(), kind, elem, mapKey, pf.zigzag(), wire, packed);
            ordered.add(fi);
            byNumber.put(pf.value(), fi);
        }
        return new StructInfo(List.copyOf(ordered), Map.copyOf(byNumber));
    }

    private static Class<?> elementClass(Field f, FieldKind kind) {
        if (kind == FieldKind.LIST) {
            if (f.getGenericType() instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
            return Object.class;
        }
        if (kind == FieldKind.MAP) {
            // For maps, "elementClass" is the value type; key type is read separately.
            if (f.getGenericType() instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 2
                    && pt.getActualTypeArguments()[1] instanceof Class<?> c) {
                return c;
            }
            return Object.class;
        }
        return f.getType();
    }

    private static Class<?> mapKeyClass(Field f) {
        if (f.getGenericType() instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length == 2
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    // -- marshal -------------------------------------------------------------

    private static void marshalStruct(CodedOutputStream out, Object obj, int depth, int maxDepth) throws IOException {
        StructInfo info = info(obj.getClass());
        for (FieldInfo fi : info.ordered) {
            try {
                marshalField(out, fi, fi.field.get(obj), depth, maxDepth);
            } catch (IllegalAccessException e) {
                throw new IOException("field " + fi.field.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void marshalField(CodedOutputStream out, FieldInfo fi, Object value, int depth, int maxDepth)
            throws IOException {
        if (value == null) return;

        if (fi.kind == FieldKind.LIST) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return;
            // Repeated numeric scalars: packed (proto3 default) — one
            // LEN-typed record carrying the concatenated element encodings,
            // every element emitted since packed encoding has no per-element
            // presence. Other element types: one record per element, a zero
            // element written as its zero record so the list length survives.
            if (isPackable(fi.elementClass)) {
                marshalPacked(out, fi.number, fi.elementClass, list, fi.zigzag);
                return;
            }
            for (Object elem : list) {
                marshalScalar(out, fi.number, fi.elementClass, elem == null ? scalarZero(fi.elementClass) : elem,
                        fi.zigzag, true, depth, maxDepth);
            }
            return;
        }

        if (fi.kind == FieldKind.MAP) {
            // proto3 maps: each entry is a length-prefixed MapEntry message
            // with key at field 1 and value at field 2. Both fields are
            // always written, zero-valued or not — presence lives in the
            // entry, not in its fields — which is the layout protobuf-go,
            // protoc and C++ protobuf write (protowire#295, #78). Keys and
            // values inherit the field's zigzag flag.
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) return;
            // A map entry is a descent, as a decoder counts it.
            if (depth + 1 > maxDepth) {
                throw new IOException("nesting depth exceeds MaxNestingDepth=" + maxDepth);
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ByteArrayOutputStream entryBuf = new ByteArrayOutputStream();
                CodedOutputStream entryOut = CodedOutputStream.newInstance(entryBuf);
                Object k = e.getKey() == null ? scalarZero(fi.mapKeyClass) : e.getKey();
                Object v = e.getValue() == null ? scalarZero(fi.elementClass) : e.getValue();
                marshalScalar(entryOut, 1, fi.mapKeyClass, k, fi.zigzag, true, depth + 1, maxDepth);
                marshalScalar(entryOut, 2, fi.elementClass, v, fi.zigzag, true, depth + 1, maxDepth);
                entryOut.flush();
                out.writeByteArray(fi.number, entryBuf.toByteArray());
            }
            return;
        }

        marshalScalar(out, fi.number, fi.field.getType(), value, fi.zigzag, false, depth, maxDepth);
    }

    /**
     * Whether a {@link List} of this element type is encoded packed: the
     * numeric scalars and bool, which proto3 packs by default. String,
     * bytes, big numbers, messages and maps are never packed.
     */
    private static boolean isPackable(Class<?> type) {
        return type == boolean.class || type == Boolean.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class;
    }

    private static boolean isSignedInt(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class;
    }

    private static void marshalPacked(CodedOutputStream out, int num, Class<?> type, List<?> list, boolean zigzag)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CodedOutputStream payload = CodedOutputStream.newInstance(buf);
        for (Object elem : list) {
            Object v = elem == null ? scalarZero(type) : elem;
            if (type == boolean.class || type == Boolean.class) {
                payload.writeBoolNoTag((Boolean) v);
            } else if (isSignedInt(type)) {
                long n = ((Number) v).longValue();
                payload.writeUInt64NoTag(zigzag ? zigzagEncode(n) : n);
            } else if (type == float.class || type == Float.class) {
                payload.writeFixed32NoTag(Float.floatToIntBits(((Number) v).floatValue()));
            } else {
                payload.writeFixed64NoTag(Double.doubleToLongBits(((Number) v).doubleValue()));
            }
        }
        payload.flush();
        out.writeByteArray(num, buf.toByteArray());
    }

    /**
     * Writes one scalar or message value. A singular field ({@code element}
     * false) skips its zero value, as proto3 does; a list element or a map
     * key / value ({@code element} true) is always written.
     */
    private static void marshalScalar(CodedOutputStream out, int num, Class<?> type, Object v,
                                      boolean zigzag, boolean element, int depth, int maxDepth) throws IOException {
        if (type == boolean.class || type == Boolean.class) {
            boolean b = (Boolean) v;
            if (!b && !element) return;
            out.writeBool(num, b);
        } else if (isSignedInt(type)) {
            long n = ((Number) v).longValue();
            if (n == 0 && !element) return;
            // proto3 int32 / int64: a plain varint, sign-extended to 64 bits
            // for a negative value — what protowire-go's pb, protobuf-go and
            // protoc write. Zigzag (sint32 / sint64) only on opt-in (#77).
            out.writeTag(num, WireFormat.WIRETYPE_VARINT);
            out.writeUInt64NoTag(zigzag ? zigzagEncode(n) : n);
        } else if (type == float.class || type == Float.class) {
            float f = ((Number) v).floatValue();
            if (f == 0f && !element) return;
            // floatToIntBits, not the raw form writeFloat uses: a struct
            // field is a language value, so any NaN goes out as the canonical
            // 0x7fc00000, payload or not (draft -01 § Numeric Literals; #161).
            out.writeFixed32(num, Float.floatToIntBits(f));
        } else if (type == double.class || type == Double.class) {
            double d = ((Number) v).doubleValue();
            if (d == 0d && !element) return;
            // Likewise 0x7ff8000000000000 for any NaN (#161).
            out.writeFixed64(num, Double.doubleToLongBits(d));
        } else if (type == String.class) {
            String s = (String) v;
            if (s.isEmpty() && !element) return;
            out.writeString(num, s);
        } else if (type == byte[].class) {
            byte[] b = (byte[]) v;
            if (b.length == 0 && !element) return;
            out.writeByteArray(num, b);
        } else if (type == BigInteger.class || type == BigDecimal.class || type == BigFloat.class) {
            // A null element or map value is the zero value's record, an
            // empty message, as protowire-go writes a nil *big.Int /
            // *big.Rat / *big.Float element (#159): the list keeps its
            // length and the entry its value field. A null singular field
            // never reaches here.
            if (v == null) {
                out.writeByteArray(num, new byte[0]);
            } else if (v instanceof BigInteger bi) {
                if (bi.signum() == 0 && !element) return;
                out.writeByteArray(num, marshalBigInteger(bi));
            } else if (v instanceof BigDecimal bd) {
                if (bd.signum() == 0 && !element) return;
                out.writeByteArray(num, marshalBigDecimal(bd));
            } else {
                BigFloats.Value bf = normalize((BigFloat) v);
                // Only +0 at prec 0 is the zero value; a zero at a precision,
                // or -0, is written (protowire#410).
                if (bf.prec() == 0 && !bf.negative() && !element) return;
                out.writeByteArray(num, marshalBigFloat(bf));
            }
        } else if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            // unsigned variants would land here only if user used custom types — fallback
            long n = ((Number) v).longValue();
            if (n == 0 && !element) return;
            out.writeUInt64(num, n);
        } else {
            // nested message; an absent (null) singular message is omitted,
            // a null element is its zero record.
            if (v == null) {
                if (!element) return;
                out.writeByteArray(num, new byte[0]);
                return;
            }
            out.writeByteArray(num, marshalNested(v, depth + 1, maxDepth));
        }
    }

    // -- unmarshal -----------------------------------------------------------

    private static void unmarshalStruct(CodedInputStream in, Object dest, int depth, UnmarshalOptions opts) throws IOException {
        StructInfo info = info(dest.getClass());
        while (true) {
            int tag = in.readTag();
            if (tag == 0) return;
            int num = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            FieldInfo fi = info.byNumber.get(num);
            if (fi == null || !fi.accepts(wireType)) {
                // An unknown field, or a known one whose wire type does not
                // match its type, which protobuf treats as unknown (#257).
                skipField(in, tag, "");
                continue;
            }
            try {
                consumeField(in, dest, fi, wireType, depth, opts);
            } catch (IllegalAccessException e) {
                throw new IOException("field " + fi.field.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void checkRepeated(int size, UnmarshalOptions opts, String what) throws IOException {
        if (size >= opts.maxRepeatedCount()) {
            throw new IOException(what + " field exceeds MaxRepeatedCount=" + opts.maxRepeatedCount());
        }
    }

    private static void consumeField(CodedInputStream in, Object dest, FieldInfo fi, int wireType, int depth,
                                     UnmarshalOptions opts) throws IOException, IllegalAccessException {
        if (fi.kind == FieldKind.LIST) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) fi.field.get(dest);
            if (list == null) {
                list = new ArrayList<>();
                fi.field.set(dest, list);
            }
            // Packed repeated numerics (proto3 default): a LEN-typed record
            // carrying concatenated element encodings. Each element is read
            // with its natural encoding; unpacked records decode as before.
            if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED && isPackable(fi.elementClass)) {
                int len = in.readRawVarint32();
                int limit = in.pushLimit(len);
                int elemWire = (fi.elementClass == float.class || fi.elementClass == Float.class)
                        ? WireFormat.WIRETYPE_FIXED32
                        : (fi.elementClass == double.class || fi.elementClass == Double.class)
                        ? WireFormat.WIRETYPE_FIXED64 : WireFormat.WIRETYPE_VARINT;
                while (in.getBytesUntilLimit() > 0) {
                    checkRepeated(list.size(), opts, "repeated");
                    list.add(readScalar(in, fi.elementClass, elemWire, depth, fi.zigzag, opts));
                }
                in.popLimit(limit);
                return;
            }
            checkRepeated(list.size(), opts, "repeated");
            list.add(readScalar(in, fi.elementClass, wireType, depth, fi.zigzag, opts));
            return;
        }
        if (fi.kind == FieldKind.MAP) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) fi.field.get(dest);
            if (map == null) {
                map = new HashMap<>();
                fi.field.set(dest, map);
            }
            byte[] entryBytes = in.readByteArray();
            // A map entry is a nested message on the wire: one level, like
            // protowire-go's unmarshalField(entry, ..., depth+1).
            if (depth + 1 > opts.maxNestingDepth()) {
                throw new IOException("nesting depth exceeds MaxNestingDepth=" + opts.maxNestingDepth());
            }
            CodedInputStream entryIn = CodedInputStream.newInstance(entryBytes);
            Object key = scalarZero(fi.mapKeyClass);
            Object val = scalarZero(fi.elementClass);
            while (true) {
                int tag = entryIn.readTag();
                if (tag == 0) break;
                int n = WireFormat.getTagFieldNumber(tag);
                int wt = WireFormat.getTagWireType(tag);
                if (n == 1 && wt == wireTypeOf(fi.mapKeyClass)) {
                    key = readScalar(entryIn, fi.mapKeyClass, wt, depth + 1, fi.zigzag, opts);
                } else if (n == 2 && wt == wireTypeOf(fi.elementClass)) {
                    val = readScalar(entryIn, fi.elementClass, wt, depth + 1, fi.zigzag, opts);
                } else {
                    // Unknown, or a key or value whose wire type does not match.
                    skipField(entryIn, tag, " in map entry");
                }
            }
            if (!map.containsKey(key)) checkRepeated(map.size(), opts, "map");
            map.put(key, val);
            return;
        }
        Object value = readScalar(in, fi.field.getType(), wireType, depth, fi.zigzag, opts);
        fi.field.set(dest, value);
    }

    /**
     * The wire type a value of this type is written with, and so the one it
     * is read from. It mirrors {@link #readScalar}'s dispatch: a type that
     * method does not name is read as a nested message.
     */
    private static int wireTypeOf(Class<?> type) {
        if (type == boolean.class || type == Boolean.class || isSignedInt(type)) return WireFormat.WIRETYPE_VARINT;
        if (type == float.class || type == Float.class) return WireFormat.WIRETYPE_FIXED32;
        if (type == double.class || type == Double.class) return WireFormat.WIRETYPE_FIXED64;
        return WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }

    /**
     * Consumes the value of a field read as unknown, as protobuf-go's
     * {@code protowire.ConsumeFieldValue} does, and refuses what it refuses:
     * wire types 6 and 7, an end-group that ends nothing, and a value cut
     * short. The error is protowire-go's, {@code corrupt field <n><where>:
     * <cause>}, where {@code where} names the message the field is in
     * ({@code " in BigInt"}), if any. A group is skipped by protobuf-java,
     * whose nesting bound of 100 is HARDENING.md's {@code MaxNestingDepth},
     * and its error is the cause.
     */
    static void skipField(CodedInputStream in, int tag, String where) throws IOException {
        try {
            switch (WireFormat.getTagWireType(tag)) {
                case WireFormat.WIRETYPE_VARINT -> skipVarint(in);
                case WireFormat.WIRETYPE_FIXED64 -> skipBytes(in, 8);
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    long len = skipVarint(in);
                    // A length past an int cannot fit what is left of a byte[].
                    if (len < 0 || len > Integer.MAX_VALUE) throw new SkipError(UNEXPECTED_EOF);
                    skipBytes(in, (int) len);
                }
                case WireFormat.WIRETYPE_START_GROUP -> {
                    try {
                        in.skipField(tag);
                    } catch (InvalidProtocolBufferException e) {
                        throw new SkipError(e.getMessage());
                    }
                }
                case WireFormat.WIRETYPE_FIXED32 -> skipBytes(in, 4);
                case WireFormat.WIRETYPE_END_GROUP -> throw new SkipError("proto: mismatching end group marker");
                default -> throw new SkipError("proto: cannot parse reserved wire type");
            }
        } catch (SkipError e) {
            throw new IOException("corrupt field " + WireFormat.getTagFieldNumber(tag) + where + ": " + e.getMessage());
        }
    }

    /** {@code io.ErrUnexpectedEOF}'s text, protobuf-go's cause for a value cut short. */
    private static final String UNEXPECTED_EOF = "unexpected EOF";

    /** The cause of a failed skip, in protobuf-go's words; {@link #skipField} names the field. */
    private static final class SkipError extends IOException {
        SkipError(String cause) { super(cause); }
    }

    /** One varint, as protobuf-go's {@code ConsumeVarint}: ten bytes at most, the tenth 0 or 1. */
    private static long skipVarint(CodedInputStream in) throws IOException {
        long v = 0;
        for (int i = 0; ; i++) {
            if (in.isAtEnd()) throw new SkipError(UNEXPECTED_EOF);
            int b = in.readRawByte() & 0xff;
            if (i == 9 && b > 1) throw new SkipError("proto: variable length integer overflow");
            v |= (long) (b & 0x7f) << (7 * i);
            if (b < 0x80) return v;
        }
    }

    private static void skipBytes(CodedInputStream in, int n) throws IOException {
        try {
            in.skipRawBytes(n);
        } catch (InvalidProtocolBufferException e) {
            // n is not negative, so the one failure is running out of bytes.
            throw new SkipError(UNEXPECTED_EOF);
        }
    }

    private static Object scalarZero(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return Boolean.FALSE;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (type == String.class) return "";
        if (type == byte[].class) return new byte[0];
        return null;
    }

    private static Object readScalar(CodedInputStream in, Class<?> type, int wireType, int depth, boolean zigzag,
                                     UnmarshalOptions opts) throws IOException {
        if (type == boolean.class || type == Boolean.class) {
            return in.readBool();
        }
        if (isSignedInt(type)) {
            // proto3 int32 / int64 read the varint as a two's-complement
            // value (a negative int32 arrives sign-extended to ten bytes and
            // narrows back); sint32 / sint64 decode zigzag on opt-in (#77).
            long raw = in.readRawVarint64();
            long n = zigzag ? zigzagDecode(raw) : raw;
            if (type == int.class || type == Integer.class) return (int) n;
            if (type == short.class || type == Short.class) return (short) n;
            if (type == byte.class || type == Byte.class) return (byte) n;
            return n;
        }
        if (type == float.class || type == Float.class) {
            return in.readFloat();
        }
        if (type == double.class || type == Double.class) {
            return in.readDouble();
        }
        if (type == String.class) {
            // HARDENING.md § UTF-8: no lossy decode into a string field.
            return in.readStringRequireUtf8();
        }
        if (type == byte[].class) {
            return in.readByteArray();
        }
        if (type == BigInteger.class) {
            return unmarshalBigInteger(in.readByteArray());
        }
        if (type == BigDecimal.class) {
            return unmarshalBigDecimal(in.readByteArray(), opts.maxNumericLiteralDigits());
        }
        if (type == BigFloat.class) {
            return unmarshalBigFloat(in.readByteArray(), opts.maxBigFloatExponent(), opts.maxNumericLiteralDigits());
        }
        // nested message
        byte[] sub = in.readByteArray();
        return unmarshalNested(sub, type, depth + 1, opts);
    }

    // -- zigzag --------------------------------------------------------------

    static long zigzagEncode(long v) { return (v << 1) ^ (v >> 63); }
    static long zigzagDecode(long v) { return (v >>> 1) ^ -(v & 1); }

    // -- big number nested encoders -----------------------------------------

    static byte[] marshalBigInteger(BigInteger bi) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        byte[] abs = bi.abs().toByteArray();
        abs = stripLeadingZero(abs);
        if (abs.length > 0) out.writeByteArray(1, abs);
        if (bi.signum() < 0) out.writeBool(2, true);
        out.flush();
        return baos.toByteArray();
    }

    static BigInteger unmarshalBigInteger(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        byte[] abs = new byte[0];
        boolean negative = false;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) break;
            // A field whose wire type does not match is skipped as unknown (#257).
            switch (bigField(tag, BIG_INT_WIRE)) {
                case 1 -> abs = in.readByteArray();
                case 2 -> negative = in.readBool();
                default -> skipField(in, tag, " in BigInt");
            }
        }
        if (abs.length == 0) return BigInteger.ZERO;
        BigInteger v = new BigInteger(1, abs);
        return negative ? v.negate() : v;
    }

    /**
     * Writes {@code bd} in reduced form: the smallest non-negative scale that
     * holds the value exactly, so {@code 1.00} goes out as unscaled 1 and no
     * scale, {@code 5E+2} as unscaled 500, and a zero as an empty message.
     * That is what protowire-go writes from its scale-less {@code big.Rat},
     * and what the spec requires of a producer writing from a language value
     * (protowire#508; #154). The scale the caller held is not part of the
     * value, so it is not kept. A reduced scale past
     * {@link #MAX_NUMERIC_LITERAL_DIGITS} is refused: a conformant reader,
     * this module's included, would reject the bytes.
     */
    static byte[] marshalBigDecimal(BigDecimal bd) throws IOException {
        if (bd.signum() == 0) return new byte[0];
        bd = bd.stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0);
        if (bd.scale() > MAX_NUMERIC_LITERAL_DIGITS) {
            throw new IOException("pxf.Decimal scale " + bd.scale() + " exceeds MaxNumericLiteralDigits=" + MAX_NUMERIC_LITERAL_DIGITS);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        BigInteger unscaled = bd.unscaledValue().abs();
        byte[] absBytes = stripLeadingZero(unscaled.toByteArray());
        int scale = bd.scale();
        boolean negative = bd.signum() < 0;
        if (absBytes.length > 0) out.writeByteArray(1, absBytes);
        if (scale != 0) {
            // `int32 scale = 2` in pxf/bignum.proto: a PLAIN varint, with a
            // negative value sign-extended to 64 bits. Zigzag is sint32,
            // which this field is not (protowire-go#92; #77).
            out.writeTag(2, WireFormat.WIRETYPE_VARINT);
            out.writeUInt64NoTag(scale);
        }
        if (negative) out.writeBool(3, true);
        out.flush();
        return baos.toByteArray();
    }

    static BigDecimal unmarshalBigDecimal(byte[] data) throws IOException {
        return unmarshalBigDecimal(data, MAX_NUMERIC_LITERAL_DIGITS);
    }

    static BigDecimal unmarshalBigDecimal(byte[] data, int maxDigits) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        byte[] abs = new byte[0];
        int scale = 0;
        boolean negative = false;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) break;
            switch (bigField(tag, DECIMAL_WIRE)) {
                case 1 -> abs = in.readByteArray();
                case 2 -> scale = (int) in.readRawVarint64();
                case 3 -> negative = in.readBool();
                default -> skipField(in, tag, " in Decimal");
            }
        }
        if (scale > maxDigits || scale < -maxDigits) {
            throw new IOException("pxf.Decimal scale " + scale + " exceeds MaxNumericLiteralDigits=" + maxDigits);
        }
        BigInteger unscaled = abs.length == 0 ? BigInteger.ZERO : new BigInteger(1, abs);
        BigDecimal bd = new BigDecimal(unscaled, scale);
        return negative ? bd.negate() : bd;
    }

    /**
     * The record's value in the form a decoder holds it, which is what
     * protowire-go writes for a {@code big.Float} (#159): the mantissa at
     * exactly {@code prec} bits, a wider one rounded half even and a
     * narrower one widened, and no exponent for a zero. A prec-0 record is
     * ±0 whatever its mantissa says. {@link BigFloats#normalize} is the
     * rule, shared with the PXF codecs. A value a conformant reader would
     * refuse — a prec past {@link #MAX_NUMERIC_LITERAL_DIGITS}, a binary
     * exponent past {@link #MAX_BIG_FLOAT_EXPONENT} — is refused here.
     */
    static BigFloats.Value normalize(BigFloat bf) throws IOException {
        byte[] mant = bf.mantissa() == null ? new byte[0] : bf.mantissa();
        try {
            return BigFloats.normalize(new BigInteger(1, mant), bf.exponent(), bf.prec(), bf.negative(),
                    MAX_NUMERIC_LITERAL_DIGITS, MAX_BIG_FLOAT_EXPONENT);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static byte[] marshalBigFloat(BigFloats.Value bf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        byte[] mant = stripLeadingZero(bf.mantissa().toByteArray());
        if (mant.length > 0) out.writeByteArray(1, mant);
        // `int32 exponent = 2`: a plain varint, a negative one sign-extended
        // to ten bytes (#77).
        if (bf.exponent() != 0) out.writeInt32(2, bf.exponent());
        if (bf.prec() != 0) out.writeUInt32(3, bf.prec());
        if (bf.negative()) out.writeBool(4, true);
        out.flush();
        return baos.toByteArray();
    }

    /**
     * Refuses a value past the bounds before anything is built from it, as
     * protowire-go does: a prec whose literal needs more than
     * {@code maxDigits} digits (protowire#499), and a binary exponent past
     * {@code maxExponent} (protowire#281). A zero mantissa has no binary
     * exponent, and a prec-0 message is zero at any exponent (#159).
     */
    static BigFloat unmarshalBigFloat(byte[] data, int maxExponent, int maxDigits) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        byte[] mantissa = new byte[0];
        int exponent = 0;
        int prec = 0;
        boolean negative = false;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) break;
            switch (bigField(tag, BIG_FLOAT_WIRE)) {
                case 1 -> mantissa = in.readByteArray();
                case 2 -> exponent = (int) in.readRawVarint64();
                case 3 -> prec = in.readUInt32();
                case 4 -> negative = in.readBool();
                default -> skipField(in, tag, " in BigFloat");
            }
        }
        if (prec == 0) return new BigFloat(mantissa, exponent, prec, negative);
        long n = BigFloats.digitsFor(Integer.toUnsignedLong(prec));
        if (n > maxDigits) {
            throw new IOException("pxf.BigFloat prec " + Integer.toUnsignedLong(prec) + " implies " + n
                    + " digits; MaxNumericLiteralDigits=" + maxDigits);
        }
        int first = 0;
        while (first < mantissa.length && mantissa[first] == 0) first++;
        if (first < mantissa.length) {
            int bits = 8 * (mantissa.length - first - 1) + (32 - Integer.numberOfLeadingZeros(mantissa[first] & 0xff));
            long binary = (long) exponent + bits;
            if (binary > maxExponent || binary < -maxExponent) {
                throw new IOException("pxf.BigFloat binary exponent " + binary + " exceeds MaxBigFloatExponent=" + maxExponent);
            }
        }
        return new BigFloat(mantissa, exponent, prec, negative);
    }

    // The wire type of each field of a big-number message, field 1 first
    // (pxf/bignum.proto).
    private static final int[] BIG_INT_WIRE = {
        WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.WIRETYPE_VARINT};  // abs, negative
    private static final int[] DECIMAL_WIRE = {
        WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.WIRETYPE_VARINT,
        WireFormat.WIRETYPE_VARINT};                                        // unscaled, scale, negative
    private static final int[] BIG_FLOAT_WIRE = {
        WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.WIRETYPE_VARINT,
        WireFormat.WIRETYPE_VARINT, WireFormat.WIRETYPE_VARINT};            // mantissa, exponent, prec, negative

    /**
     * The field number of a big-number message's tag when its wire type is
     * the one {@code wireTypes} declares for that field, and 0 otherwise, so
     * a mistyped or unknown field goes to {@link #skipField}.
     */
    private static int bigField(int tag, int[] wireTypes) {
        int num = WireFormat.getTagFieldNumber(tag);
        return num >= 1 && num <= wireTypes.length
            && WireFormat.getTagWireType(tag) == wireTypes[num - 1] ? num : 0;
    }

    private static byte[] stripLeadingZero(byte[] b) {
        if (b.length > 1 && b[0] == 0) return Arrays.copyOfRange(b, 1, b.length);
        if (b.length == 1 && b[0] == 0) return new byte[0];
        return b;
    }
}
