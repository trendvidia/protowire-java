// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Format#formatValue} and {@link Format#appendValue} render one value
 * as protowire-go's {@code FormatValue} does (#210): the text a document
 * writes for it, multi-line values indented two spaces with no trailing
 * newline. Each expected text is protowire-go v1.17.0's {@code FormatValue}
 * for the same assignment's value.
 */
class FormatValueTest {

    /** {@code <assignment>} to Go's FormatValue of its value. */
    private static final Map<String, String> GO = Map.ofEntries(
        Map.entry("s = \"a\\\"b\\n\\tc é\"", "\"a\\\"b\\n\\tc é\""),
        Map.entry("i = -42", "-42"),
        Map.entry("big = 123456789012345678901234567890", "123456789012345678901234567890"),
        Map.entry("f = 1.5e-7", "1.5e-7"),
        Map.entry("p = 1.5p53", "1.5p53"),
        Map.entry("b = b\"aGVsbG8=\"", "b\"aGVsbG8=\""),
        Map.entry("e = COLOR_RED", "COLOR_RED"),
        Map.entry("t = true", "true"),
        Map.entry("n = null", "null"),
        Map.entry("ts = 2026-01-02T03:04:05.123Z", "2026-01-02T03:04:05.123Z"),
        Map.entry("d = 1h30m", "1h30m"),
        Map.entry("l = [1, 2, 3]", "[\n  1,\n  2,\n  3\n]"),
        Map.entry("ll = [[1, 2], []]", "[\n  [\n    1,\n    2\n  ],\n  [\n  ]\n]"),
        Map.entry("lb = [{ a = 1 }, { b = \"x\" }]", "[\n  {\n    a = 1\n  },\n  {\n    b = \"x\"\n  }\n]"),
        Map.entry("blk = { a = 1  inner { c = [1] } }", "{\n  a = 1\n  inner {\n    c = [\n      1\n    ]\n  }\n}"),
        Map.entry("m = { \"k\": 1  2: \"two\" }", "{\n  k: 1\n  2: \"two\"\n}"),
        Map.entry("empty = {}", "{\n}"),
        Map.entry("el = []", "[\n]"));

    @Test
    void eachValueIsWrittenAsGoWritesIt() {
        GO.forEach((line, want) -> {
            Ast.Assignment a = (Ast.Assignment) Parser.parse(line).entries().get(0);
            assertEquals(want, Format.formatValue(a.value()), line);
            StringBuilder sb = new StringBuilder("x = ");
            assertEquals("x = " + want, Format.appendValue(sb, a.value()).toString(), line);
        });
    }
}
