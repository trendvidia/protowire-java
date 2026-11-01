// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

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

    /**
     * The message types one PXF literal can denote — the set
     * {@code applyMessageDefault} honors and draft -01
     * §annotation-extensions "Default Placement" fixes: Timestamp,
     * Duration, the nine {@code *Value} wrappers, {@code pxf.BigInt},
     * {@code pxf.Decimal}, {@code pxf.BigFloat}, and nothing else.
     */
    public static boolean isDefaultableMessage(Descriptor md) {
        return isTimestamp(md) || isDuration(md) || isWrapper(md) || isBigInt(md) || isDecimal(md) || isBigFloat(md);
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
        // A Decimal is unscaled × 10^(-scale), so writing it out materialises
        // |scale| digits — work proportional to a value the input sets in
        // five bytes. A scale is a digit count, which is why the literal
        // digit cap is the bound rather than a new one (HARDENING.md
        // § Mandatory limits, protowire#279).
        if (scale > Limits.MAX_NUMERIC_LITERAL_DIGITS || scale < -Limits.MAX_NUMERIC_LITERAL_DIGITS) {
            throw new PxfException(Position.UNKNOWN,
                    "pxf.Decimal scale " + scale + " exceeds MaxNumericLiteralDigits=" + Limits.MAX_NUMERIC_LITERAL_DIGITS);
        }
        BigInteger unscaled = abs.size() == 0 ? BigInteger.ZERO : new BigInteger(1, abs.toByteArray());
        String digits = unscaled.toString(10);
        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('-');
        if (scale < 0) {
            // value = unscaled × 10^(-scale): a negative scale is trailing
            // zeros (bounded above, so at most MaxNumericLiteralDigits of
            // them). This port's own writers never produce one; bytes from
            // another producer can (#85).
            sb.append(digits);
            if (unscaled.signum() != 0) sb.append("0".repeat(-scale));
            return sb.toString();
        }
        if (scale == 0) { sb.append(digits); return sb.toString(); }
        while (digits.length() <= scale) digits = "0" + digits;
        sb.append(digits, 0, digits.length() - scale).append('.').append(digits, digits.length() - scale, digits.length());
        return sb.toString();
    }

    /**
     * Sets a {@code pxf.BigFloat} to {@code bd} read at
     * {@value BigFloats#DEFAULT_PREC} bits, as an unsuffixed literal is.
     *
     * @deprecated a BigDecimal has no precision or negative zero to keep; use
     *     {@link #setBigFloat(Message.Builder, BigFloats.Value)}.
     */
    @Deprecated
    public static void setBigFloat(Message.Builder b, BigDecimal bd) {
        setBigFloat(b, BigFloats.parse(bd.toString(), Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT));
    }

    /** Stores {@code v}'s fields, omitting the proto3 zero ones. */
    public static void setBigFloat(Message.Builder b, BigFloats.Value v) {
        Descriptor d = b.getDescriptorForType();
        if (v.mantissa().signum() != 0) {
            b.setField(d.findFieldByName("mantissa"), com.google.protobuf.ByteString.copyFrom(trimSign(v.mantissa().toByteArray())));
        }
        if (v.exponent() != 0) b.setField(d.findFieldByName("exponent"), v.exponent());
        if (v.prec() != 0) b.setField(d.findFieldByName("prec"), v.prec());
        if (v.negative()) b.setField(d.findFieldByName("negative"), true);
    }

    /**
     * Writes a {@code pxf.BigFloat} as its literal, suffix included: see
     * {@link BigFloats}.
     *
     * @throws PxfException on a value past {@code MaxBigFloatExponent} or
     *     {@code MaxNumericLiteralDigits}: a decoder would refuse the literal
     */
    public static String readBigFloatStr(Message msg) {
        Descriptor d = msg.getDescriptorForType();
        com.google.protobuf.ByteString abs = (com.google.protobuf.ByteString) msg.getField(d.findFieldByName("mantissa"));
        int exp = (Integer) msg.getField(d.findFieldByName("exponent"));
        int prec = (Integer) msg.getField(d.findFieldByName("prec"));
        boolean neg = (Boolean) msg.getField(d.findFieldByName("negative"));
        BigInteger mant = abs.isEmpty() ? BigInteger.ZERO : new BigInteger(1, abs.toByteArray());
        try {
            return BigFloats.format(mant, exp, prec, neg, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
        } catch (IllegalArgumentException e) {
            throw new PxfException(Position.UNKNOWN, e.getMessage());
        }
    }

    public static BigInteger parseBigInt(String s) { return parseBigInt(s, Limits.MAX_NUMERIC_LITERAL_DIGITS); }

    /** As {@link #parseBigInt(String)} under a per-call digit cap. */
    public static BigInteger parseBigInt(String s, int maxDigits) {
        checkLiteralDigits(s, maxDigits);
        return new BigInteger(s, 10);
    }

    /** parseDecimal returns [unscaled (BigInteger, abs), scale (Integer), negative (Boolean)]. */
    public static Object[] parseDecimal(String s) { return parseDecimal(s, Limits.MAX_NUMERIC_LITERAL_DIGITS); }

    /** As {@link #parseDecimal(String)} under a per-call digit cap. */
    public static Object[] parseDecimal(String s, int maxDigits) {
        checkLiteralDigits(s, maxDigits);
        boolean neg = false;
        if (!s.isEmpty() && s.charAt(0) == '-') { neg = true; s = s.substring(1); }
        int dot = s.indexOf('.');
        int scale = 0;
        if (dot >= 0) {
            scale = s.length() - dot - 1;
            s = s.substring(0, dot) + s.substring(dot + 1);
        }
        // Leading zeros are not significant (protowire#410), so the digit cap
        // no longer bounds the scale: "0.000…1" counts one digit however many
        // places it has. The scale is bounded as it is on the PB wire.
        if (scale > maxDigits) {
            throw new PxfException(Position.UNKNOWN, "pxf.Decimal scale " + scale + " exceeds MaxNumericLiteralDigits=" + maxDigits);
        }
        BigInteger unscaled = new BigInteger(s, 10);
        return new Object[] { unscaled, scale, neg };
    }

    /**
     * The decimal value a BigFloat literal writes, without reading it at a
     * binary precision.
     *
     * @deprecated use {@link BigFloats#parse(String, int, int)}, which reads
     *     the literal as the stored value.
     */
    @Deprecated
    public static BigDecimal parseBigFloat(String s) { return parseBigFloat(s, Limits.MAX_NUMERIC_LITERAL_DIGITS); }

    /**
     * As {@link #parseBigFloat(String)} under a per-call digit cap.
     *
     * @deprecated see {@link #parseBigFloat(String)}.
     */
    @Deprecated
    public static BigDecimal parseBigFloat(String s, int maxDigits) {
        checkLiteralDigits(s, maxDigits);
        int p = s.indexOf('p');
        return new BigDecimal(p < 0 ? s : s.substring(0, p));
    }

    /**
     * HARDENING.md § Mandatory limits, {@code MaxNumericLiteralDigits}: the
     * digit count of a literal about to be handed to a big-number parser
     * (#81). Enforced in the parse helpers rather than the lexer so that a
     * {@code (pxf.default)} literal, which never passes through the lexer,
     * is under the same limit. A literal short enough to be within the
     * limit needs no counting at all, and that is every literal a real
     * document carries.
     */
    static void checkLiteralDigits(String s, int max) {
        if (s.length() <= max) return;
        int n = BigFloats.countDigits(s);
        if (n > max) {
            throw new PxfException(Position.UNKNOWN,
                    "numeric literal has " + n + " digits; MaxNumericLiteralDigits=" + max);
        }
    }

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
