// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireWriter;

/**
 * A pxf.BigFloat literal past MaxBigFloatExponent is refused with
 * protowire-go's text, except far past it, where Java names it in its own
 * words: a pinned divergence, as the owner decided on #229.
 * {@code scripts/go-bigfloat-text/go.tsv} is Go's verdict on each literal of
 * {@code literals.txt}, decoded as {@code bf = <literal>} into bf.v1.H; the
 * conformance job reruns it against protowire-go main, so a change in Go's
 * text fails there. A literal here gets Go's text, or, if PINNED lists it,
 * Java's pinned text while Go's differs: a pinned literal that comes to
 * match Go fails, so the list only shrinks.
 *
 * <p>Why Java's words: BigFloats.parse refuses a literal more than
 * log10(2) x MaxBigFloatExponent + 2 decades out before it computes anything,
 * so a thirteen-byte literal such as 1e99999999 never builds 10^99999999. Go
 * names the value's binary exponent after rounding it to its precision,
 * which can carry it to the next power of two; matching that would mean
 * porting big.Float's binary rounding to produce an error text. The verdict
 * is the same.
 */
class BigFloatTextLiteTest {

    /** The literals Java refuses in its own words, and those words. */
    static final Map<String, String> PINNED = Map.of(
            "1e4935", "1:6: big float literal 1e4935 is past MaxBigFloatExponent=16384",
            "1e5000", "1:6: big float literal 1e5000 is past MaxBigFloatExponent=16384",
            "1e-4949", "1:6: big float literal 1e-4949 is past MaxBigFloatExponent=16384",
            "1e-4950", "1:6: big float literal 1e-4950 is past MaxBigFloatExponent=16384",
            "1e99999999", "1:6: big float literal 1e99999999 is past MaxBigFloatExponent=16384",
            "1e-99999999", "1:6: big float literal 1e-99999999 is past MaxBigFloatExponent=16384");

    private static String verdict(String lit) {
        try {
            LiteWireWriter.encode(Parser.parse("bf = " + lit), LiteCorpusTest.meta("bf.v1.H"));
            return "ok";
        } catch (PxfException e) {
            return e.getMessage();
        }
    }

    @Test
    void eachLiteralGetsGosTextOrItsPinnedOne() throws IOException {
        Path p = Path.of(System.getProperty("protowire.bigFloatText", "bigFloatText-unset"));
        List<String[]> rows = Files.readAllLines(p).stream().filter(l -> !l.isEmpty()).map(l -> l.split("\t", -1)).toList();
        assertEquals(11, rows.size(), "go.tsv's literals");
        for (String[] r : rows) {
            String pinned = PINNED.get(r[0]);
            if (pinned == null) {
                assertEquals(r[1], verdict(r[0]), r[0]);
            } else {
                assertEquals(pinned, verdict(r[0]), r[0]);
                assertNotEquals(pinned, r[1], r[0] + " matches Go now: drop it from PINNED (#229)");
            }
        }
        assertEquals(6, rows.stream().filter(r -> PINNED.containsKey(r[0])).count(), "PINNED names literals go.tsv lacks");
    }
}
