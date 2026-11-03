// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.android.LiteWireReader;

import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A field with no presence whose value on the wire is its zero value prints
 * nothing, as protowire-go and the full tier print it (#180). Each expected
 * text is the output of protowire-go's check_decode --format pb-text for the
 * same bytes, with \n for line breaks.
 */
final class ImplicitZeroLiteTest {

    private static final Map<String, PxfMeta> METAS = Map.of(
        "Scalars", text.v1.ScalarsPxfMeta.INSTANCE,
        "Shapes", text.v1.ShapesPxfMeta.INSTANCE);

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        // A plain field holding its zero value: integer, string, bool, enum, double.
        "Scalars | 2800               | ''",
        "Scalars | 4a00               | ''",
        "Scalars | 4000               | ''",
        "Scalars | 7000               | ''",
        "Scalars | 090000000000000000 | ''",
        // -0.0 has a bit set, so it is kept.
        "Scalars | 090000000000000080 | f_double = -0\\n",
        // The last value wins: 7 then 0 is 0, which prints nothing; 0 then 7 is 7.
        "Scalars | 28072800           | ''",
        "Scalars | 28002807           | f_int32 = 7\\n",
        // Inside a nested message and a list element.
        "Shapes  | 0a020a00           | one {\\n}\\n",
        "Shapes  | 32020a00           | items = [\\n  {\\n  }\\n]\\n",
        // Controls: an optional field keeps its zero, and so does a map entry.
        "Shapes  | 1800               | opt_int = 0\\n",
        "Shapes  | 1200               | opt_string = \"\"\\n",
        "Shapes  | 52020800           | by_int = {\\n  0: \"\"\\n}\\n",
    })
    void printsWhatGoPrints(String schema, String hex, String text) {
        String doc = Format.formatDocument(LiteWireReader.toAst(
            HexFormat.of().parseHex(hex), METAS.get(schema), DecodeLimits.defaults()));
        assertEquals(text.replace("\\n", "\n"), doc);
    }
}
