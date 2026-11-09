// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireWriter;
import reqnull.v1.OuterPxfMeta;
import reqnull.v1.ReqPxfMeta;

/**
 * A required field holds a value, so {@code null} on it is rejected as
 * absence is (protowire#569, #189), whichever spelling marks it required and
 * whatever its type or presence, in a body and a nested body. Mirrors
 * protowire-go's TestRequiredRejectsNull and pxf's test of the same name;
 * the {@code @dataset} cells of those are not here, since the lite tier binds
 * no {@code @dataset} rows yet (#212).
 */
class RequiredNullTest {

    private static final List<String> FIELDS = List.of("r", "a", "w", "s", "plain");
    private static final Map<String, String> VALUES = Map.of("r", "0", "a", "0", "w", "0", "s", "{}", "plain", "0");

    private static String body(String nulled) {
        StringBuilder b = new StringBuilder();
        for (String f : FIELDS) b.append(f).append(" = ").append(f.equals(nulled) ? "null" : VALUES.get(f)).append('\n');
        return b.toString();
    }

    private static void req(String doc) {
        LiteWireWriter.encode(Parser.parse(doc.getBytes(StandardCharsets.UTF_8)), ReqPxfMeta.INSTANCE);
    }

    private static void outer(String doc) {
        LiteWireWriter.encode(Parser.parse(doc.getBytes(StandardCharsets.UTF_8)), OuterPxfMeta.INSTANCE);
    }

    private static void rejects(String want, Executable call) {
        PxfException e = assertThrows(PxfException.class, call);
        assertTrue(e.getMessage().contains(want), e.getMessage());
    }

    @Test
    void zeroValuesSatisfyEveryRequiredField() {
        assertDoesNotThrow(() -> req(body("")));
        assertDoesNotThrow(() -> outer("in {\n" + body("") + "}\n"));
    }

    @Test
    void requiredFieldsAreNotNullable() {
        assertTrue(ReqPxfMeta.INSTANCE.nullableFields().isEmpty(), ReqPxfMeta.INSTANCE.nullableFields().toString());
        assertTrue(ReqPxfMeta.INSTANCE.requiredFields().containsAll(List.of(1, 2, 3, 4, 5)),
            ReqPxfMeta.INSTANCE.requiredFields().toString());
    }

    @TestFactory
    List<DynamicTest> nullOnARequiredFieldIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String f : FIELDS) {
            String want = "null is not allowed on required field \"" + f + "\": a required field holds a value";
            tests.add(DynamicTest.dynamicTest(f, () -> {
                // At the null literal: line of the entry, past "<name> = ".
                String at = (FIELDS.indexOf(f) + 1) + ":" + (f.length() + 4) + ": ";
                rejects(at + want, () -> req(body(f)));
                rejects(want, () -> outer("in {\n" + body(f) + "}\n"));
            }));
        }
        return tests;
    }
}
