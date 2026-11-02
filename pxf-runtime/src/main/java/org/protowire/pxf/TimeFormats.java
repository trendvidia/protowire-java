// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Pure JDK timestamp / duration parse and format helpers used by the PXF
 * lexer and parser. Lifted out of {@code WellKnown} so the descriptor-free
 * pxf-runtime module can call them without pulling in protobuf-java; the
 * descriptor-coupled half of {@code WellKnown} delegates to these methods
 * to preserve its public API.
 */
public final class TimeFormats {
    private TimeFormats() {}

    /** Parses an RFC 3339 (with optional fractional seconds) timestamp. */
    public static Instant parseRfc3339(String raw) {
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid timestamp: " + raw, e);
        }
    }

    /** Format an Instant as RFC 3339 with nanosecond precision when needed. */
    public static String formatRfc3339(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t.atOffset(ZoneOffset.UTC).toInstant());
    }

    /**
     * Parse a Go-style duration: optional leading sign, then sequence of
     * {@code <number>(ns|us|µs|ms|s|m|h)}.
     */
    public static Duration parseGoDuration(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("empty duration");
        String s = raw;
        boolean neg = false;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            neg = s.charAt(0) == '-';
            s = s.substring(1);
        }
        if (s.isEmpty()) throw new IllegalArgumentException("invalid duration: " + raw);
        if ("0".equals(s)) return Duration.ZERO;

        // Exact, like Go's time.ParseDuration: each segment is
        // digits[.digits] × unit in integer arithmetic, truncated to whole
        // nanoseconds, so "1.234567ms" reads back as exactly 1234567ns and
        // "1.001ms" as 1001000ns — through a double, 1.001 × 1e6 is
        // 1000999.9999999999 and truncates one short (554 of the 9999
        // three-decimal millisecond values do). The magnitude is
        // summed before the sign is applied so "-2562047h47m16.854775808s",
        // Go's minimum, fits; anything past ±2^63-1 ns is an invalid
        // duration, as it is in Go.
        java.math.BigInteger nanosTotal = java.math.BigInteger.ZERO;
        int i = 0;
        while (i < s.length()) {
            // number
            int numStart = i;
            while (i < s.length() && (isAsciiDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (numStart == i) throw new IllegalArgumentException("invalid duration: " + raw);
            String num = s.substring(numStart, i);
            // unit
            int unitStart = i;
            while (i < s.length() && !isAsciiDigit(s.charAt(i)) && s.charAt(i) != '.') i++;
            if (unitStart == i) throw new IllegalArgumentException("missing unit: " + raw);
            String unit = s.substring(unitStart, i);
            long unitNanos = switch (unit) {
                case "ns" -> 1L;
                case "us", "µs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60L * 1_000_000_000L;
                case "h" -> 3600L * 1_000_000_000L;
                default -> throw new IllegalArgumentException("unknown unit " + unit + " in " + raw);
            };
            java.math.BigDecimal magnitude;
            try {
                magnitude = new java.math.BigDecimal(num);
            } catch (NumberFormatException e) {
                // "1.", ".5", "1.2.3": not a duration-segment (§3.3).
                throw new IllegalArgumentException("invalid duration: " + raw);
            }
            nanosTotal = nanosTotal.add(magnitude.multiply(java.math.BigDecimal.valueOf(unitNanos))
                    .setScale(0, java.math.RoundingMode.DOWN).toBigIntegerExact());
        }
        if (neg) nanosTotal = nanosTotal.negate();
        try {
            return Duration.ofNanos(nanosTotal.longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("duration out of range: " + raw);
        }
    }

    private static boolean isAsciiDigit(char c) { return c >= '0' && c <= '9'; }

    /**
     * Whether a {@code google.protobuf.Duration} with these fields can be
     * written as a literal: a literal holds a whole number of nanoseconds in
     * 64 bits, about 292 years, as Go's {@code time.Duration} does. Past that
     * the value is written as a block (draft -01 § Timestamps and Durations),
     * which no decoder limits.
     */
    public static boolean fitsDurationLiteral(long seconds, int nanos) {
        // Well inside the limit whatever nanos holds; int32 is about 2.1s.
        if (seconds > -9_223_372_030L && seconds < 9_223_372_030L) return true;
        return java.math.BigInteger.valueOf(seconds)
                .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                .add(java.math.BigInteger.valueOf(nanos))
                .bitLength() < Long.SIZE;
    }

    /**
     * Format a Duration as Go's {@code time.Duration.String()} does, byte for
     * byte: "1h30m0s", "1.5s", "1m0s", "1.5ms", "312.5µs", "1ns", "0s". Below
     * one second a single unit carries a decimal fraction; from one second up
     * the seconds segment is always written, and the minutes segment too once
     * there are hours.
     */
    public static String formatGoDuration(Duration d) {
        if (d.isZero()) return "0s";
        long nanos = d.toNanos();
        StringBuilder sb = new StringBuilder();
        // The magnitude in unsigned arithmetic: -Long.MIN_VALUE overflows to
        // itself, which read unsigned is exactly 2^63 — the magnitude wanted.
        // Once the hours are out the remainder is below 3.6e12 and signed
        // arithmetic is safe again.
        long mag = nanos;
        if (nanos < 0) { sb.append('-'); mag = -nanos; }
        if (Long.compareUnsigned(mag, 1_000_000_000L) < 0) {
            if (mag < 1_000L) return sb.append(mag).append("ns").toString();
            if (mag < 1_000_000L) return appendFraction(sb, mag, 1_000L).append("µs").toString();
            return appendFraction(sb, mag, 1_000_000L).append("ms").toString();
        }
        long h = Long.divideUnsigned(mag, 3_600_000_000_000L);
        mag = Long.remainderUnsigned(mag, 3_600_000_000_000L);
        long m = mag / 60_000_000_000L;
        mag %= 60_000_000_000L;
        if (h > 0) sb.append(h).append('h');
        if (h > 0 || m > 0) sb.append(m).append('m');
        return appendFraction(sb, mag, 1_000_000_000L).append('s').toString();
    }

    /** Appends value/unit in decimal, without trailing fraction zeros; unit is a power of ten. */
    private static StringBuilder appendFraction(StringBuilder sb, long value, long unit) {
        sb.append(value / unit);
        long frac = value % unit;
        if (frac == 0) return sb;
        // unit + frac zero-pads the fraction to the unit's width, behind a leading 1.
        String digits = Long.toString(unit + frac);
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') end--;
        return sb.append('.').append(digits, 1, end);
    }
}
