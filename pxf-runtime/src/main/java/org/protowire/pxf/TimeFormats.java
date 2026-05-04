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

        long nanosTotal = 0;
        int i = 0;
        while (i < s.length()) {
            // number
            int numStart = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (numStart == i) throw new IllegalArgumentException("invalid duration: " + raw);
            String num = s.substring(numStart, i);
            // unit
            int unitStart = i;
            while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '.') i++;
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
            double f = Double.parseDouble(num);
            nanosTotal = Math.addExact(nanosTotal, (long) (f * unitNanos));
        }
        if (neg) nanosTotal = -nanosTotal;
        return Duration.ofNanos(nanosTotal);
    }

    /** Format a Duration in Go style: e.g. "1h30m", "30s", "100ms". */
    public static String formatGoDuration(Duration d) {
        if (d.isZero()) return "0s";
        long nanos = d.toNanos();
        StringBuilder sb = new StringBuilder();
        if (nanos < 0) { sb.append('-'); nanos = -nanos; }
        long h = nanos / 3_600_000_000_000L; nanos %= 3_600_000_000_000L;
        long m = nanos / 60_000_000_000L;    nanos %= 60_000_000_000L;
        long s = nanos / 1_000_000_000L;     nanos %= 1_000_000_000L;
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (s > 0 || nanos > 0 || sb.length() <= (sb.length() > 0 && sb.charAt(0) == '-' ? 1 : 0)) {
            if (nanos == 0) {
                sb.append(s).append('s');
            } else if (nanos % 1_000_000 == 0) {
                long ms = s * 1000 + nanos / 1_000_000;
                sb.append(ms).append("ms");
            } else if (nanos % 1000 == 0) {
                long us = s * 1_000_000 + nanos / 1000;
                sb.append(us).append("us");
            } else {
                long ns = s * 1_000_000_000L + nanos;
                sb.append(ns).append("ns");
            }
        }
        return sb.toString();
    }
}
