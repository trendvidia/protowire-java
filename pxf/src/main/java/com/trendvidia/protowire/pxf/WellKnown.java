package com.trendvidia.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Detection and accessors for well-known proto types and PXF big-num types.
 *
 * <p>Mirrors the Go module's {@code wellknown.go}.
 */
public final class WellKnown {
    private WellKnown() {}

    /** Wrapper full names → inner field's proto kind name. */
    static final Map<String, FieldDescriptor.Type> WRAPPER_TYPES = Map.ofEntries(
            Map.entry("google.protobuf.BoolValue",   FieldDescriptor.Type.BOOL),
            Map.entry("google.protobuf.BytesValue",  FieldDescriptor.Type.BYTES),
            Map.entry("google.protobuf.DoubleValue", FieldDescriptor.Type.DOUBLE),
            Map.entry("google.protobuf.FloatValue",  FieldDescriptor.Type.FLOAT),
            Map.entry("google.protobuf.Int32Value",  FieldDescriptor.Type.INT32),
            Map.entry("google.protobuf.Int64Value",  FieldDescriptor.Type.INT64),
            Map.entry("google.protobuf.StringValue", FieldDescriptor.Type.STRING),
            Map.entry("google.protobuf.UInt32Value", FieldDescriptor.Type.UINT32),
            Map.entry("google.protobuf.UInt64Value", FieldDescriptor.Type.UINT64));

    public static boolean isWrapper(Descriptor d) { return WRAPPER_TYPES.containsKey(d.getFullName()); }
    public static boolean isTimestamp(Descriptor d) { return "google.protobuf.Timestamp".equals(d.getFullName()); }
    public static boolean isDuration(Descriptor d) { return "google.protobuf.Duration".equals(d.getFullName()); }
    public static boolean isAny(Descriptor d) { return "google.protobuf.Any".equals(d.getFullName()); }
    public static boolean isFieldMask(Descriptor d) { return "google.protobuf.FieldMask".equals(d.getFullName()); }

    public static boolean isBigInt(Descriptor d) { return "pxf.BigInt".equals(d.getFullName()); }
    public static boolean isDecimal(Descriptor d) { return "pxf.Decimal".equals(d.getFullName()); }
    public static boolean isBigFloat(Descriptor d) { return "pxf.BigFloat".equals(d.getFullName()); }

    // -- timestamp ----------------------------------------------------------

    /** @deprecated delegates to {@link TimeFormats#parseRfc3339(String)}; prefer that directly. */
    @Deprecated
    public static Instant parseRfc3339(String raw) { return TimeFormats.parseRfc3339(raw); }

    /** @deprecated delegates to {@link TimeFormats#formatRfc3339(Instant)}; prefer that directly. */
    @Deprecated
    public static String formatRfc3339(Instant t) { return TimeFormats.formatRfc3339(t); }

    public static void setTimestamp(Message.Builder bldr, Instant t) {
        Descriptor d = bldr.getDescriptorForType();
        bldr.setField(d.findFieldByName("seconds"), t.getEpochSecond());
        bldr.setField(d.findFieldByName("nanos"), t.getNano());
    }

    public static Instant readTimestamp(Message msg) {
        Descriptor d = msg.getDescriptorForType();
        long secs = (Long) msg.getField(d.findFieldByName("seconds"));
        int nanos = (int)(long)(Long.class.cast(boxLong(msg.getField(d.findFieldByName("nanos")))));
        return Instant.ofEpochSecond(secs, nanos);
    }

    private static Long boxLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return (long) i;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }

    // -- duration -----------------------------------------------------------

    /** @deprecated delegates to {@link TimeFormats#parseGoDuration(String)}; prefer that directly. */
    @Deprecated
    public static Duration parseGoDuration(String raw) { return TimeFormats.parseGoDuration(raw); }

    /** @deprecated delegates to {@link TimeFormats#formatGoDuration(Duration)}; prefer that directly. */
    @Deprecated
    public static String formatGoDuration(Duration d) { return TimeFormats.formatGoDuration(d); }

    public static void setDuration(Message.Builder b, Duration d) {
        Descriptor desc = b.getDescriptorForType();
        long total = d.getSeconds() * 1_000_000_000L + d.getNano();
        long secs = total / 1_000_000_000L;
        int nanos = (int) (total - secs * 1_000_000_000L);
        b.setField(desc.findFieldByName("seconds"), secs);
        b.setField(desc.findFieldByName("nanos"), nanos);
    }

    public static Duration readDuration(Message m) {
        Descriptor d = m.getDescriptorForType();
        long secs = (Long) m.getField(d.findFieldByName("seconds"));
        int nanos = (int)(long) boxLong(m.getField(d.findFieldByName("nanos")));
        return Duration.ofSeconds(secs, nanos);
    }

    // -- bignum -----------------------------------------------------------

    public static void setBigInt(Message.Builder b, BigInteger bi) {
        Descriptor d = b.getDescriptorForType();
        BigInteger abs = bi.abs();
        byte[] absBytes = abs.signum() == 0 ? new byte[0] : trimSign(abs.toByteArray());
        if (absBytes.length > 0) {
            b.setField(d.findFieldByName("abs"), com.google.protobuf.ByteString.copyFrom(absBytes));
        }
        if (bi.signum() < 0) b.setField(d.findFieldByName("negative"), true);
    }

    public static BigInteger readBigInt(Message m) {
        Descriptor d = m.getDescriptorForType();
        com.google.protobuf.ByteString abs = (com.google.protobuf.ByteString) m.getField(d.findFieldByName("abs"));
        boolean neg = (Boolean) m.getField(d.findFieldByName("negative"));
        BigInteger v = abs.size() == 0 ? BigInteger.ZERO : new BigInteger(1, abs.toByteArray());
        return neg ? v.negate() : v;
    }

    public static void setDecimal(Message.Builder b, BigDecimal bd) {
        Descriptor d = b.getDescriptorForType();
        BigInteger unscaled = bd.unscaledValue().abs();
        byte[] absBytes = unscaled.signum() == 0 ? new byte[0] : trimSign(unscaled.toByteArray());
        if (absBytes.length > 0) {
            b.setField(d.findFieldByName("unscaled"), com.google.protobuf.ByteString.copyFrom(absBytes));
        }
        int scale = bd.scale();
        if (scale != 0) b.setField(d.findFieldByName("scale"), scale);
        if (bd.signum() < 0) b.setField(d.findFieldByName("negative"), true);
    }

    public static String readDecimalStr(Message m) {
        Descriptor d = m.getDescriptorForType();
        com.google.protobuf.ByteString abs = (com.google.protobuf.ByteString) m.getField(d.findFieldByName("unscaled"));
        int scale = (Integer) m.getField(d.findFieldByName("scale"));
        boolean neg = (Boolean) m.getField(d.findFieldByName("negative"));
        BigInteger unscaled = abs.size() == 0 ? BigInteger.ZERO : new BigInteger(1, abs.toByteArray());
        String digits = unscaled.toString(10);
        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('-');
        if (scale <= 0) { sb.append(digits); return sb.toString(); }
        while (digits.length() <= scale) digits = "0" + digits;
        sb.append(digits, 0, digits.length() - scale).append('.').append(digits, digits.length() - scale, digits.length());
        return sb.toString();
    }

    public static void setBigFloat(Message.Builder b, BigDecimal bd) {
        // Java port stores as decimal: mantissa = unscaledValue, exponent = -scale (as power-of-10),
        // prec = mantissa bit length, negative = sign.
        Descriptor d = b.getDescriptorForType();
        BigInteger m = bd.unscaledValue().abs();
        byte[] mantBytes = m.signum() == 0 ? new byte[0] : trimSign(m.toByteArray());
        int prec = m.bitLength();
        if (prec == 0) prec = 53; // safe default
        if (mantBytes.length > 0) {
            b.setField(d.findFieldByName("mantissa"), com.google.protobuf.ByteString.copyFrom(mantBytes));
        }
        // We encode the decimal exponent via the dedicated "exponent" field with a flag in prec
        // mirroring Go's binary representation isn't 100% lossless across decimal systems —
        // the round-trip path reads/writes via BigDecimal text representation.
        if (-bd.scale() != 0) b.setField(d.findFieldByName("exponent"), -bd.scale());
        b.setField(d.findFieldByName("prec"), prec);
        if (bd.signum() < 0) b.setField(d.findFieldByName("negative"), true);
    }

    public static String readBigFloatStr(Message msg) {
        Descriptor d = msg.getDescriptorForType();
        com.google.protobuf.ByteString abs = (com.google.protobuf.ByteString) msg.getField(d.findFieldByName("mantissa"));
        int exp = (Integer) msg.getField(d.findFieldByName("exponent"));
        boolean neg = (Boolean) msg.getField(d.findFieldByName("negative"));
        BigInteger mant = abs.size() == 0 ? BigInteger.ZERO : new BigInteger(1, abs.toByteArray());
        BigDecimal v = new BigDecimal(mant, -exp);
        if (neg) v = v.negate();
        return v.toPlainString();
    }

    public static BigInteger parseBigInt(String s) { return new BigInteger(s, 10); }

    /** parseDecimal returns [unscaled (BigInteger, abs), scale (Integer), negative (Boolean)]. */
    public static Object[] parseDecimal(String s) {
        boolean neg = false;
        if (!s.isEmpty() && s.charAt(0) == '-') { neg = true; s = s.substring(1); }
        int dot = s.indexOf('.');
        int scale = 0;
        if (dot >= 0) {
            scale = s.length() - dot - 1;
            s = s.substring(0, dot) + s.substring(dot + 1);
        }
        BigInteger unscaled = new BigInteger(s, 10);
        return new Object[] { unscaled, scale, neg };
    }

    public static BigDecimal parseBigFloat(String s) { return new BigDecimal(s); }

    /** Strip a leading 0x00 sign byte produced by {@link BigInteger#toByteArray()}. */
    private static byte[] trimSign(byte[] b) {
        if (b.length > 1 && b[0] == 0) {
            byte[] out = new byte[b.length - 1];
            System.arraycopy(b, 1, out, 0, out.length);
            return out;
        }
        return b;
    }

    // -- _null FieldMask --------------------------------------------------

    public static FieldDescriptor findNullMaskField(Descriptor d) {
        FieldDescriptor f = d.findFieldByName("_null");
        if (f == null) return null;
        if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && isFieldMask(f.getMessageType())) {
            return f;
        }
        return null;
    }
}
