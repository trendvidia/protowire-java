// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.runtime;

import java.math.BigDecimal;

import static org.protowire.sbe.runtime.SbeConstants.*;

/**
 * Field kinds and SBE primitives as the wire codec and the template check
 * compare them (protowire-go#252, #250). A kind is a
 * {@code FieldDescriptorProto.Type} number; a range's upper bound is held as
 * an unsigned long. Shared by both tiers, so the lite tier refuses what the
 * full tier does.
 */
public final class SbeKinds {
    private SbeKinds() {}

    public static final int K_DOUBLE = 1;
    public static final int K_FLOAT = 2;
    public static final int K_INT64 = 3;
    public static final int K_UINT64 = 4;
    public static final int K_INT32 = 5;
    public static final int K_FIXED64 = 6;
    public static final int K_FIXED32 = 7;
    public static final int K_BOOL = 8;
    public static final int K_STRING = 9;
    public static final int K_GROUP = 10;
    public static final int K_MESSAGE = 11;
    public static final int K_BYTES = 12;
    public static final int K_UINT32 = 13;
    public static final int K_ENUM = 14;
    public static final int K_SFIXED32 = 15;
    public static final int K_SFIXED64 = 16;
    public static final int K_SINT32 = 17;
    public static final int K_SINT64 = 18;

    /** Every SBE header field is 16 bits: blockLength, templateId, schemaId, version, numInGroup. */
    public static final int MAX_UINT16 = 0xFFFF;

    /** A kind as protowire-go's {@code protoreflect.Kind} names it: int32, string, enum. */
    public static String kindName(int kind) {
        return switch (kind) {
            case K_DOUBLE -> "double";
            case K_FLOAT -> "float";
            case K_INT64 -> "int64";
            case K_UINT64 -> "uint64";
            case K_INT32 -> "int32";
            case K_FIXED64 -> "fixed64";
            case K_FIXED32 -> "fixed32";
            case K_BOOL -> "bool";
            case K_STRING -> "string";
            case K_GROUP -> "group";
            case K_MESSAGE -> "message";
            case K_BYTES -> "bytes";
            case K_UINT32 -> "uint32";
            case K_ENUM -> "enum";
            case K_SFIXED32 -> "sfixed32";
            case K_SFIXED64 -> "sfixed64";
            case K_SINT32 -> "sint32";
            case K_SINT64 -> "sint64";
            default -> "kind " + kind;
        };
    }

    public static boolean isIntEncoding(String enc) {
        return switch (enc) {
            case ENC_INT8, ENC_INT16, ENC_INT32, ENC_INT64, ENC_UINT8, ENC_UINT16, ENC_UINT32, ENC_UINT64 -> true;
            default -> false;
        };
    }

    public static boolean isFloatEncoding(String enc) {
        return ENC_FLOAT.equals(enc) || ENC_DOUBLE.equals(enc);
    }

    /** Whether a field of this kind holds its integer as an unsigned value. */
    public static boolean unsignedKind(int kind) {
        return kind == K_BOOL || kind == K_UINT32 || kind == K_FIXED32 || kind == K_UINT64 || kind == K_FIXED64;
    }

    /** {lo, hi} of an integer, bool or enum field; an enum is open in proto3, so any int32. */
    public static long[] kindRange(int kind) {
        return switch (kind) {
            case K_INT32, K_SINT32, K_SFIXED32, K_ENUM -> new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
            case K_INT64, K_SINT64, K_SFIXED64 -> new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
            case K_UINT32, K_FIXED32 -> new long[] {0, 0xFFFFFFFFL};
            case K_BOOL -> new long[] {0, 1};
            default -> new long[] {0, -1L}; // uint64, fixed64
        };
    }

    /** {lo, hi} of an integer primitive {@code size} bytes wide. */
    public static long[] encRange(String enc, int size) {
        int bits = 8 * size;
        return enc.charAt(0) == 'i'
                ? new long[] {-1L << (bits - 1), (1L << (bits - 1)) - 1}
                : new long[] {0, -1L >>> (64 - bits)};
    }

    /** Whether {@code v}, unsigned when {@code unsigned} and an int64's bits otherwise, is within [lo, hi]. */
    public static boolean inRange(long v, boolean unsigned, long lo, long hi) {
        if (!unsigned && v < 0) return v >= lo;
        return Long.compareUnsigned(v, hi) <= 0;
    }

    /** NaN and the infinities fit, as float has them; a finite value past ±MaxFloat32 does not. */
    public static boolean floatFits(double f) {
        return Double.isInfinite(f) || !(f > Float.MAX_VALUE || f < -Float.MAX_VALUE);
    }

    /** An integer as protowire-go's intString writes it. */
    public static String intString(long v, boolean unsigned) {
        return unsigned ? Long.toUnsignedString(v) : Long.toString(v);
    }

    /**
     * {@code f} as Go's {@code %g} writes it: the shortest digits that read
     * back, in exponent form when the exponent is below -4 or at least 6,
     * as {@code 1e+39} or {@code 3.4028234663852886e+38}.
     */
    public static String goG(double f) {
        if (Double.isNaN(f)) return "NaN";
        if (Double.isInfinite(f)) return f > 0 ? "+Inf" : "-Inf";
        if (f == 0) return (1 / f < 0) ? "-0" : "0";
        BigDecimal d = new BigDecimal(Double.toString(Math.abs(f))).stripTrailingZeros();
        String digits = d.unscaledValue().toString();
        int exp = digits.length() - 1 - d.scale(); // the decimal exponent of the first digit
        String sign = f < 0 ? "-" : "";
        if (exp < -4 || exp >= 6) {
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int a = Math.abs(exp);
            return sign + mant + "e" + (exp < 0 ? "-" : "+") + (a < 10 ? "0" : "") + a;
        }
        return sign + d.toPlainString();
    }
}
