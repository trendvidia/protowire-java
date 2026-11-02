// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Float and Timestamp text, each expected string being what Go wrote:
 * {@code strconv.FormatFloat(v, 'g', -1, bits)} and {@code time.RFC3339Nano}.
 * The text gate of protowire#503 compares whole documents against Go (#147).
 */
final class FloatTextTest {

    @Test
    void doubles() {
        assertEquals("0", Format.formatDouble(0));
        assertEquals("-0", Format.formatDouble(-0.0));
        assertEquals("100", Format.formatDouble(100));
        assertEquals("1.5", Format.formatDouble(1.5));
        assertEquals("0.1", Format.formatDouble(0.1));
        assertEquals("0.0001", Format.formatDouble(1e-4));
        assertEquals("1e-05", Format.formatDouble(1e-5));
        assertEquals("999999", Format.formatDouble(999999));
        assertEquals("1e+06", Format.formatDouble(1e6));
        assertEquals("1.234567e+06", Format.formatDouble(1234567));
        assertEquals("1e+21", Format.formatDouble(1e21));
        assertEquals("1.7976931348623157e+308", Format.formatDouble(Double.MAX_VALUE));
    }

    // Java keeps two digits here, and one reads back.
    @Test
    void smallestValuesTakeOneDigit() {
        assertEquals("5e-324", Format.formatDouble(Double.MIN_VALUE));
        assertEquals("1e-323", Format.formatDouble(2 * Double.MIN_VALUE));
        assertEquals("1e-45", Format.formatFloat(Float.MIN_VALUE));
    }

    @Test
    void floats() {
        assertEquals("0.1", Format.formatFloat(0.1f));
        assertEquals("1e+10", Format.formatFloat(1e10f));
        assertEquals("-3", Format.formatFloat(-3f));
    }

    @Test
    void nonFinite() {
        assertEquals("nan", Format.formatDouble(Double.NaN));
        assertEquals("inf", Format.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-inf", Format.formatFloat(Float.NEGATIVE_INFINITY));
    }

    @Test
    void nonFiniteLiteralsParse() {
        assertEquals(Double.NaN, Format.parseDouble("nan"));
        assertEquals(Double.POSITIVE_INFINITY, Format.parseDouble("inf"));
        assertEquals(Double.POSITIVE_INFINITY, Format.parseDouble("+inf"));
        assertEquals(Float.NEGATIVE_INFINITY, Format.parseFloat("-inf"));
    }

    @Test
    void timestampFractionIsTrimmed() {
        assertEquals("2024-01-15T10:30:45Z", TimeFormats.formatRfc3339(Instant.ofEpochSecond(1705314645)));
        assertEquals("2024-01-15T10:30:45.5Z", TimeFormats.formatRfc3339(Instant.ofEpochSecond(1705314645, 500_000_000)));
        assertEquals("2024-01-15T10:30:45.1234Z", TimeFormats.formatRfc3339(Instant.ofEpochSecond(1705314645, 123_400_000)));
        assertEquals("2024-01-15T10:30:45.000000001Z", TimeFormats.formatRfc3339(Instant.ofEpochSecond(1705314645, 1)));
    }
}
