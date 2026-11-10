// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A timestamp literal is accepted or refused as protowire-go's lexer takes
 * it, and read as the instant Go reads it (#247). {@code timestamps/go.tsv}
 * is Go's verdict on {@code Parse("t = <literal>")} for each literal of
 * {@code timestamps/variants.txt}, and the Unix seconds and nanosecond of
 * each it accepts, written by {@code scripts/go-timestamps/main.go}; the
 * conformance job reruns it against protowire-go main.
 */
class TimestampGrammarTest {

    @Test
    void eachLiteralGetsGosVerdictAndInstant() throws IOException {
        List<String> differ = new ArrayList<>();
        int rows = 0, accepted = 0;
        try (InputStream in = TimestampGrammarTest.class.getResourceAsStream("/timestamps/go.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", -1);
                rows++;
                String verdict;
                try {
                    Parser.parse("t = " + f[0]);
                    verdict = "ok";
                } catch (PxfException e) {
                    verdict = e.getMessage();
                }
                String got = verdict;
                if (verdict.equals("ok")) {
                    Instant t = TimeFormats.parseRfc3339(f[0]);
                    got += "\t" + t.getEpochSecond() + "\t" + t.getNano();
                    accepted++;
                } else {
                    got += "\t\t";
                }
                String want = f[1] + "\t" + f[2] + "\t" + f[3];
                if (!got.equals(want)) differ.add(f[0] + "\n  go:   " + want + "\n  java: " + got);
            }
        }
        assertEquals(58, rows, "go.tsv's literals");
        assertTrue(differ.isEmpty(), differ.size() + " differ from Go:\n" + differ.stream().collect(Collectors.joining("\n")));
        assertEquals(29, accepted, "literals Go accepts");
    }
}
