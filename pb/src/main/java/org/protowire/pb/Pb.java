// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

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
 * </ul>
 *
 * <p>Supported types: {@code boolean}, all integer primitives + boxed, {@code float}, {@code double},
 * {@link String}, {@code byte[]}, {@link BigInteger}, {@link BigDecimal}, nested classes, {@link List}
 * of any of the above, {@link Map} with scalar keys.
 */
public final class Pb {

    private Pb() {}

    public static byte[] marshal(Object obj) throws IOException {
        if (obj == null) throw new IllegalArgumentException("pb.marshal: object is null");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        marshalStruct(out, obj);
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

    private record FieldInfo(Field field, int number, FieldKind kind, Class<?> elementClass, Class<?> mapKeyClass,
                             boolean zigzag) {}

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
            FieldInfo fi = new FieldInfo(f, pf.value(), kind, elem, mapKey, pf.zigzag());
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

    private static void marshalStruct(CodedOutputStream out, Object obj) throws IOException {
        StructInfo info = info(obj.getClass());
        for (FieldInfo fi : info.ordered) {
            try {
                marshalField(out, fi, fi.field.get(obj));
            } catch (IllegalAccessException e) {
                throw new IOException("field " + fi.field.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void marshalField(CodedOutputStream out, FieldInfo fi, Object value) throws IOException {
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
                        fi.zigzag, true);
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
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ByteArrayOutputStream entryBuf = new ByteArrayOutputStream();
                CodedOutputStream entryOut = CodedOutputStream.newInstance(entryBuf);
                Object k = e.getKey() == null ? scalarZero(fi.mapKeyClass) : e.getKey();
                Object v = e.getValue() == null ? scalarZero(fi.elementClass) : e.getValue();
                marshalScalar(entryOut, 1, fi.mapKeyClass, k, fi.zigzag, true);
                marshalScalar(entryOut, 2, fi.elementClass, v, fi.zigzag, true);
                entryOut.flush();
                out.writeByteArray(fi.number, entryBuf.toByteArray());
            }
            return;
        }

        marshalScalar(out, fi.number, fi.field.getType(), value, fi.zigzag, false);
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
                payload.writeFloatNoTag(((Number) v).floatValue());
            } else {
                payload.writeDoubleNoTag(((Number) v).doubleValue());
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
                                      boolean zigzag, boolean element) throws IOException {
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
            out.writeFloat(num, f);
        } else if (type == double.class || type == Double.class) {
            double d = ((Number) v).doubleValue();
            if (d == 0d && !element) return;
            out.writeDouble(num, d);
        } else if (type == String.class) {
            String s = (String) v;
            if (s.isEmpty() && !element) return;
            out.writeString(num, s);
        } else if (type == byte[].class) {
            byte[] b = (byte[]) v;
            if (b.length == 0 && !element) return;
            out.writeByteArray(num, b);
        } else if (type == BigInteger.class) {
            BigInteger bi = (BigInteger) v;
            if (bi.signum() == 0 && !element) return;
            byte[] msg = marshalBigInteger(bi);
            out.writeByteArray(num, msg);
        } else if (type == BigDecimal.class) {
            BigDecimal bd = (BigDecimal) v;
            if (bd.signum() == 0 && !element) return;
            byte[] msg = marshalBigDecimal(bd);
            out.writeByteArray(num, msg);
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
            byte[] msg = marshal(v);
            out.writeByteArray(num, msg);
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
            if (fi == null) {
                in.skipField(tag);
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
                if (n == 1) {
                    key = readScalar(entryIn, fi.mapKeyClass, wt, depth + 1, fi.zigzag, opts);
                } else if (n == 2) {
                    val = readScalar(entryIn, fi.elementClass, wt, depth + 1, fi.zigzag, opts);
                } else {
                    entryIn.skipField(tag);
                }
            }
            if (!map.containsKey(key)) checkRepeated(map.size(), opts, "map");
            map.put(key, val);
            return;
        }
        Object value = readScalar(in, fi.field.getType(), wireType, depth, fi.zigzag, opts);
        fi.field.set(dest, value);
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
            int num = WireFormat.getTagFieldNumber(tag);
            switch (num) {
                case 1 -> abs = in.readByteArray();
                case 2 -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        if (abs.length == 0) return BigInteger.ZERO;
        BigInteger v = new BigInteger(1, abs);
        return negative ? v.negate() : v;
    }

    static byte[] marshalBigDecimal(BigDecimal bd) throws IOException {
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
            int num = WireFormat.getTagFieldNumber(tag);
            switch (num) {
                case 1 -> abs = in.readByteArray();
                case 2 -> scale = (int) in.readRawVarint64();
                case 3 -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        if (scale > maxDigits || scale < -maxDigits) {
            throw new IOException("pxf.Decimal scale " + scale + " exceeds MaxNumericLiteralDigits=" + maxDigits);
        }
        BigInteger unscaled = abs.length == 0 ? BigInteger.ZERO : new BigInteger(1, abs);
        BigDecimal bd = new BigDecimal(unscaled, scale);
        return negative ? bd.negate() : bd;
    }

    private static byte[] stripLeadingZero(byte[] b) {
        if (b.length > 1 && b[0] == 0) return Arrays.copyOfRange(b, 1, b.length);
        if (b.length == 1 && b[0] == 0) return new byte[0];
        return b;
    }
}
