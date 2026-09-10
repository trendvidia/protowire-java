// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tokenisation of duration literals against draft-01 §3.3 (#55):
 *
 * <pre>
 *   duration-segment = 1*DIGIT [ "." 1*DIGIT ] time-unit
 *   time-unit        = "ns" / "us" / micro-us / "ms" / "s" / "m" / "h"
 *   micro-us         = %xC2.B5 %x73    ; UTF-8 of "µs"
 * </pre>
 *
 * <p>Before #55 the lexer decided FLOAT on seeing "." before it looked for
 * a unit, so "1.5ms" came out as FLOAT "1.5" + IDENT "ms", and it never
 * admitted the two-byte "µ", so "2µs" was INT "2" + ILLEGAL. Both are what
 * the Go, Rust, C++ and TypeScript encoders write for any Duration that is
 * not a whole multiple of its largest unit ({@code time.Duration.String()}).
 * Mirrors {@code protowire-go/encoding/pxf/lexer_duration_test.go}.
 */
class LexerDurationTest {

    private record Tok(TokenKind kind, String value) {}

    private static List<Token> lexAll(String input) {
        Lexer l = new Lexer(input);
        List<Token> toks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Token t = l.next();
            if (t.kind() == TokenKind.EOF) return toks;
            toks.add(t);
        }
        return fail("lexer did not reach EOF within 64 tokens on " + input);
    }

    private static void expect(String input, Tok... want) {
        List<Token> got = lexAll(input);
        assertEquals(want.length, got.size(), () -> "tokens for " + input + ": " + got);
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i].kind(), got.get(i).kind(), "token " + i + " kind of " + input + ": " + got);
            if (want[i].value() != null) {
                assertEquals(want[i].value(), got.get(i).value(), "token " + i + " value of " + input);
            }
        }
    }

    private static Tok dur(String v)   { return new Tok(TokenKind.DURATION, v); }
    private static Tok flt(String v)   { return new Tok(TokenKind.FLOAT, v); }
    private static Tok integer(String v) { return new Tok(TokenKind.INT, v); }
    private static Tok ident(String v) { return new Tok(TokenKind.IDENT, v); }
    private static Tok illegal(String v) { return new Tok(TokenKind.ILLEGAL, v); }

    @Test
    void specExamples() {
        expect("30s", dur("30s"));
        expect("1h30m", dur("1h30m"));
        expect("500ms", dur("500ms"));
        expect("1.5h", dur("1.5h"));
        expect("2µs", dur("2µs"));
        expect("2us", dur("2us"));
    }

    // What time.Duration.String() emits for measured values.
    @Test
    void goDurationStringForms() {
        expect("1.234567ms", dur("1.234567ms"));
        expect("1.5ms", dur("1.5ms"));
        expect("312.5µs", dur("312.5µs"));
        expect("1.234µs", dur("1.234µs"));
        expect("1h30m0.5s", dur("1h30m0.5s"));
        expect("-1.5s", dur("-1.5s"));
        expect("-312.5µs", dur("-312.5µs"));
        expect("0s", dur("0s"));
    }

    @Test
    void everyUnitFractional() {
        expect("1.5ns", dur("1.5ns"));
        expect("1.5us", dur("1.5us"));
        expect("1.5µs", dur("1.5µs"));
        expect("1.5s", dur("1.5s"));
        expect("1.5m", dur("1.5m"));
    }

    // A fraction in any segment, not only the first (§3.3 puts the optional
    // fraction inside duration-segment).
    @Test
    void fractionInAnySegment() {
        expect("1h30.5m", dur("1h30.5m"));
        expect("1.5h30.5m1.5s", dur("1.5h30.5m1.5s"));
    }

    @Test
    void unchangedForms() {
        expect("1h30m500ms", dur("1h30m500ms"));
        expect("1ms234us567ns", dur("1ms234us567ns"));
        expect("250ms", dur("250ms"));
    }

    // Still a float when no unit follows.
    @Test
    void floatsStayFloats() {
        expect("1.5", flt("1.5"));
        expect("1.5e3", flt("1.5e3"));
        expect("1.5E-3", flt("1.5E-3"));
        expect("-1.5", flt("-1.5"));
        expect("1.", flt("1."));
        expect("1.e3", flt("1.e3"));
    }

    // An exponent is not a unit, so "1.5e3ms" is a float and an identifier,
    // exactly as before; a fraction with no digits after the "." is not a
    // duration-segment, so the float branch keeps it; and a non-unit letter
    // is an identifier following the number.
    @Test
    void tokenBoundariesAfterANumber() {
        expect("1.5e3ms", flt("1.5e3"), ident("ms"));
        expect("1e3s", flt("1e3"), ident("s"));
        expect("1.ms", flt("1."), ident("ms"));
        expect("1.5x", flt("1.5"), ident("x"));
        expect("1.5 x", flt("1.5"), ident("x"));
        expect("5x", integer("5"), ident("x"));
    }

    // A unit letter that starts a longer word is consumed into the duration
    // attempt and rejected there (unchanged: "5min" was already ILLEGAL);
    // the fraction does not change that.
    @Test
    void unitPrefixOfALongerWordIsOneInvalidDuration() {
        expect("1.5min", illegal("invalid duration: 1.5min"));
        expect("5min", illegal("invalid duration: 5min"));
    }

    // Only U+00B5 MICRO SIGN (C2 B5) is micro-us. U+03BC GREEK SMALL LETTER
    // MU (CE BC) is not in the grammar even though the duration parser would
    // accept it, and must not sneak in via the lexer: the "2" is an INT and
    // what follows is not a DURATION.
    @Test
    void greekMuIsNotAUnit() {
        List<Token> got = lexAll("2μs");
        assertEquals(TokenKind.INT, got.get(0).kind(), got.toString());
        assertEquals("2", got.get(0).value());
        assertEquals(TokenKind.ILLEGAL, got.get(1).kind(), got.toString());
        for (Token t : got) assertNotEquals(TokenKind.DURATION, t.kind(), got.toString());
    }

    // A bare micro sign with no "s" is not a unit either.
    @Test
    void bareMicroSignIsInvalid() {
        expect("2µ", illegal("invalid duration: 2µ"));
        expect("2µm", illegal("invalid duration: 2µm"));
    }

    // The token ends where the value ends.
    @Test
    void tokenEndsWhereTheValueEnds() {
        expect("1.5ms,", dur("1.5ms"), new Tok(TokenKind.COMMA, ","));
        expect("1.5ms]", dur("1.5ms"), new Tok(TokenKind.RBRACKET, "]"));
        expect("1.5ms}", dur("1.5ms"), new Tok(TokenKind.RBRACE, "}"));
        expect("1.5ms#c", dur("1.5ms"), new Tok(TokenKind.COMMENT, "#c"));
        expect("1.5ms\n", dur("1.5ms"), new Tok(TokenKind.NEWLINE, null));
        expect("2µs 3", dur("2µs"), integer("3"));
    }

    // -- The parser behind the token: exact, like time.ParseDuration --------

    @Test
    void fractionsParseExactly() {
        assertEquals(Duration.ofNanos(1_234_567), TimeFormats.parseGoDuration("1.234567ms"));
        // Through a double, 1.001 × 1e6 is 1000999.9999999999 and truncates
        // one nanosecond short.
        assertEquals(Duration.ofNanos(1_001_000), TimeFormats.parseGoDuration("1.001ms"));
        assertEquals(Duration.ofNanos(1_001_000_000L), TimeFormats.parseGoDuration("1.001s"));
        assertEquals(Duration.ofNanos(312_500), TimeFormats.parseGoDuration("312.5µs"));
        assertEquals(Duration.ofNanos(1), TimeFormats.parseGoDuration("1.5ns"), "sub-nanosecond truncates, as in Go");
        assertEquals(Duration.ofNanos(5_400_500_000_000L), TimeFormats.parseGoDuration("1h30m0.5s"));
        assertEquals(Duration.ofNanos(-5_400_500_000_000L), TimeFormats.parseGoDuration("-1h30m0.5s"));
    }

    // The encoder side of the same range ends: -Long.MIN_VALUE overflows,
    // and the old formatter wrote "--2562047h-47m…" for it.
    @Test
    void goRangeEndsFormat() {
        assertEquals("2562047h47m16854775807ns", TimeFormats.formatGoDuration(Duration.ofNanos(Long.MAX_VALUE)));
        assertEquals("-2562047h47m16854775808ns", TimeFormats.formatGoDuration(Duration.ofNanos(Long.MIN_VALUE)));
        assertEquals("-1ns", TimeFormats.formatGoDuration(Duration.ofNanos(-1)));
        assertEquals("1h30m", TimeFormats.formatGoDuration(Duration.ofNanos(5_400_000_000_000L)));
        assertEquals("1500ms", TimeFormats.formatGoDuration(Duration.ofNanos(1_500_000_000L)));
    }

    @Test
    void goRangeEndsParse() {
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), TimeFormats.parseGoDuration("2562047h47m16.854775807s"));
        assertEquals(Duration.ofNanos(Long.MIN_VALUE), TimeFormats.parseGoDuration("-2562047h47m16.854775808s"));
        assertThrows(IllegalArgumentException.class, () -> TimeFormats.parseGoDuration("2562047h47m16.854775808s"));
        assertThrows(IllegalArgumentException.class, () -> TimeFormats.parseGoDuration("9223372036854775808ns"));
    }
}
