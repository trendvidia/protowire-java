// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Pure JDK timestamp / duration parse and format helpers used by the PXF
 * lexer and parser. Lifted out of {@code WellKnown} so the descriptor-free
 * pxf-runtime module can call them without pulling in protobuf-java; the
 * descriptor-coupled half of {@code WellKnown} delegates to these methods
 * to preserve its public API.
 */
public final class TimeFormats {
    private TimeFormats() {}

    /**
     * Parses an RFC 3339 timestamp, with optional fractional seconds, as
     * protowire-go reads one: Go's {@code time.Parse} with the
     * {@code RFC3339Nano} layout, whose parse this ports (#247). It takes
     * {@code YYYY-MM-DDThh:mm:ss[.f…]} and {@code Z} or {@code ±hh:mm}, and
     * also what Go's layout parse lets through: a one-digit hour, an offset
     * up to {@code ±24:60}, a ',' before the fraction, and a fraction past
     * nine digits, which is cut to nine. It refuses what Go refuses: an
     * {@code +hh} or {@code +hh:mm:ss} offset, a missing second, a lowercase
     * {@code t} or {@code z}, a day past its month's last.
     *
     * @throws IllegalArgumentException {@code invalid timestamp: <raw>}
     */
    public static Instant parseRfc3339(String raw) {
        Instant t = goRfc3339(raw);
        if (t == null) throw new IllegalArgumentException("invalid timestamp: " + raw);
        return t;
    }

    /** Go's layout parse of {@code 2006-01-02T15:04:05.999999999Z07:00}, or null where it fails. */
    private static Instant goRfc3339(String s) {
        int[] i = {0};
        int year = digits(s, i, 4);
        if (year < 0 || !lit(s, i, '-')) return null;
        int month = digits(s, i, 2);
        if (month < 1 || month > 12 || !lit(s, i, '-')) return null;
        int day = digits(s, i, 2);
        if (day < 0 || !lit(s, i, 'T')) return null;
        // stdHour takes one digit or two.
        int hour = digits(s, i, 2);
        if (hour < 0) hour = digits(s, i, 1);
        if (hour < 0 || hour >= 24 || !lit(s, i, ':')) return null;
        int min = digits(s, i, 2);
        if (min < 0 || min >= 60 || !lit(s, i, ':')) return null;
        int sec = digits(s, i, 2);
        if (sec < 0 || sec >= 60) return null;
        int nanos = 0;
        if (i[0] + 1 < s.length() && (s.charAt(i[0]) == '.' || s.charAt(i[0]) == ',') && isDigit(s.charAt(i[0] + 1))) {
            int from = ++i[0];
            while (i[0] < s.length() && isDigit(s.charAt(i[0]))) i[0]++;
            // Nine digits at most count; the rest are cut, as Go's parseNanoseconds cuts them.
            String f = s.substring(from, Math.min(i[0], from + 9));
            nanos = Integer.parseInt(f) * (int) Math.pow(10, 9 - f.length());
        }
        int offset;
        if (i[0] < s.length() && s.charAt(i[0]) == 'Z') {
            offset = 0;
            i[0]++;
        } else {
            if (s.length() - i[0] < 6 || s.charAt(i[0] + 3) != ':') return null;
            char sign = s.charAt(i[0]);
            if (sign != '+' && sign != '-') return null;
            i[0]++;
            int hh = digits(s, i, 2);
            i[0]++; // ':'
            int mm = digits(s, i, 2);
            if (hh < 0 || mm < 0 || hh > 24 || mm > 60) return null;
            offset = (sign == '-' ? -1 : 1) * (hh * 3600 + mm * 60);
        }
        if (i[0] != s.length()) return null;
        if (day < 1 || day > java.time.YearMonth.of(year, month).lengthOfMonth()) return null;
        long epoch = java.time.LocalDateTime.of(year, month, day, hour, min, sec).toEpochSecond(ZoneOffset.UTC) - offset;
        return Instant.ofEpochSecond(epoch, nanos);
    }

    /** {@code n} ASCII digits at {@code i[0]}, consumed, as a number; -1, nothing consumed, when they are not there. */
    private static int digits(String s, int[] i, int n) {
        if (i[0] + n > s.length()) return -1;
        int v = 0;
        for (int k = 0; k < n; k++) {
            char c = s.charAt(i[0] + k);
            if (!isDigit(c)) return -1;
            v = v * 10 + (c - '0');
        }
        i[0] += n;
        return v;
    }

    private static boolean lit(String s, int[] i, char c) {
        if (i[0] >= s.length() || s.charAt(i[0]) != c) return false;
        i[0]++;
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Format an Instant as RFC 3339 with nanosecond precision when needed. */
    public static String formatRfc3339(Instant t) {
        String text = DateTimeFormatter.ISO_INSTANT.format(t.atOffset(ZoneOffset.UTC).toInstant());
        // ISO_INSTANT groups a fraction in threes; time.RFC3339Nano trims it.
        int dot = text.indexOf('.');
        if (dot < 0) return text;
        int end = text.length() - 1;
        while (text.charAt(end - 1) == '0') end--;
        return text.substring(0, end) + "Z";
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
