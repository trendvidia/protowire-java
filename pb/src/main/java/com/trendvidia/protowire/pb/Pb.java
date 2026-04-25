package com.trendvidia.protowire.pb;

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
 * zero-value fields are omitted. The wire format is standard protobuf binary.
 *
 * <p>Supported types: {@code boolean}, all integer primitives + boxed, {@code float}, {@code double},
 * {@link String}, {@code byte[]}, {@link BigInteger}, {@link BigDecimal}, nested classes, {@link List}
 * of any of the above.
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
        try {
            T obj = cls.getDeclaredConstructor().newInstance();
            unmarshal(data, obj);
            return obj;
        } catch (ReflectiveOperationException e) {
            throw new IOException("pb.unmarshal: cannot instantiate " + cls.getName(), e);
        }
    }

    public static void unmarshal(byte[] data, Object dest) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        unmarshalStruct(in, dest);
    }

    // -- struct info cache ---------------------------------------------------

    private record FieldInfo(Field field, int number, FieldKind kind, Class<?> elementClass) {}

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
            Class<?> elem = elementClass(f);
            FieldInfo fi = new FieldInfo(f, pf.value(), kind, elem);
            ordered.add(fi);
            byNumber.put(pf.value(), fi);
        }
        return new StructInfo(List.copyOf(ordered), Map.copyOf(byNumber));
    }

    private static Class<?> elementClass(Field f) {
        if (List.class.isAssignableFrom(f.getType())) {
            if (f.getGenericType() instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
            return Object.class;
        }
        return f.getType();
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
            for (Object elem : list) {
                if (elem == null) continue;
                marshalScalar(out, fi.number, fi.elementClass, elem);
            }
            return;
        }

        marshalScalar(out, fi.number, fi.field.getType(), value);
    }

    private static void marshalScalar(CodedOutputStream out, int num, Class<?> type, Object v) throws IOException {
        if (type == boolean.class || type == Boolean.class) {
            boolean b = (Boolean) v;
            if (!b) return;
            out.writeBool(num, true);
        } else if (type == int.class || type == Integer.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            long n = ((Number) v).longValue();
            if (n == 0) return;
            // proto3 zigzag for signed ints (matching Go's pb)
            out.writeTag(num, WireFormat.WIRETYPE_VARINT);
            out.writeUInt64NoTag(zigzagEncode(n));
        } else if (type == long.class || type == Long.class) {
            long n = ((Number) v).longValue();
            if (n == 0) return;
            out.writeTag(num, WireFormat.WIRETYPE_VARINT);
            out.writeUInt64NoTag(zigzagEncode(n));
        } else if (type == float.class || type == Float.class) {
            float f = ((Number) v).floatValue();
            if (f == 0f) return;
            out.writeFloat(num, f);
        } else if (type == double.class || type == Double.class) {
            double d = ((Number) v).doubleValue();
            if (d == 0d) return;
            out.writeDouble(num, d);
        } else if (type == String.class) {
            String s = (String) v;
            if (s.isEmpty()) return;
            out.writeString(num, s);
        } else if (type == byte[].class) {
            byte[] b = (byte[]) v;
            if (b.length == 0) return;
            out.writeByteArray(num, b);
        } else if (type == BigInteger.class) {
            BigInteger bi = (BigInteger) v;
            if (bi.signum() == 0) return;
            byte[] msg = marshalBigInteger(bi);
            out.writeByteArray(num, msg);
        } else if (type == BigDecimal.class) {
            BigDecimal bd = (BigDecimal) v;
            if (bd.signum() == 0) return;
            byte[] msg = marshalBigDecimal(bd);
            out.writeByteArray(num, msg);
        } else if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            // unsigned variants would land here only if user used custom types — fallback
            long n = ((Number) v).longValue();
            if (n == 0) return;
            out.writeUInt64(num, n);
        } else {
            // nested message
            byte[] msg = marshal(v);
            out.writeByteArray(num, msg);
        }
    }

    // -- unmarshal -----------------------------------------------------------

    private static void unmarshalStruct(CodedInputStream in, Object dest) throws IOException {
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
                consumeField(in, dest, fi, wireType);
            } catch (IllegalAccessException e) {
                throw new IOException("field " + fi.field.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void consumeField(CodedInputStream in, Object dest, FieldInfo fi, int wireType)
            throws IOException, IllegalAccessException {
        if (fi.kind == FieldKind.LIST) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) fi.field.get(dest);
            if (list == null) {
                list = new ArrayList<>();
                fi.field.set(dest, list);
            }
            list.add(readScalar(in, fi.elementClass, wireType));
            return;
        }
        Object value = readScalar(in, fi.field.getType(), wireType);
        fi.field.set(dest, value);
    }

    private static Object readScalar(CodedInputStream in, Class<?> type, int wireType) throws IOException {
        if (type == boolean.class || type == Boolean.class) {
            return in.readBool();
        }
        if (type == int.class || type == Integer.class) {
            return (int) zigzagDecode(in.readRawVarint64());
        }
        if (type == short.class || type == Short.class) {
            return (short) zigzagDecode(in.readRawVarint64());
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) zigzagDecode(in.readRawVarint64());
        }
        if (type == long.class || type == Long.class) {
            return zigzagDecode(in.readRawVarint64());
        }
        if (type == float.class || type == Float.class) {
            return in.readFloat();
        }
        if (type == double.class || type == Double.class) {
            return in.readDouble();
        }
        if (type == String.class) {
            return in.readString();
        }
        if (type == byte[].class) {
            return in.readByteArray();
        }
        if (type == BigInteger.class) {
            return unmarshalBigInteger(in.readByteArray());
        }
        if (type == BigDecimal.class) {
            return unmarshalBigDecimal(in.readByteArray());
        }
        // nested message
        byte[] sub = in.readByteArray();
        return unmarshal(sub, type);
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
            out.writeTag(2, WireFormat.WIRETYPE_VARINT);
            out.writeUInt64NoTag(zigzagEncode(scale));
        }
        if (negative) out.writeBool(3, true);
        out.flush();
        return baos.toByteArray();
    }

    static BigDecimal unmarshalBigDecimal(byte[] data) throws IOException {
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
                case 2 -> scale = (int) zigzagDecode(in.readRawVarint64());
                case 3 -> negative = in.readBool();
                default -> in.skipField(tag);
            }
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
