// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.PxfException;

/**
 * {@code testdata/annotations/} in the annotation form, which only the plugin
 * can turn into tables: {@code settings_annotated.proto} needs a v1.2
 * compiler, so {@code src/main/proto} spells it for stock protoc. The rest of
 * the spec corpus runs from its files in {@link LiteCorpusTest}.
 */
class LiteTablesTest {
    @Test
    void annotationForm_takesItsDefaults() {
        assertEquals(FixtureModesTest.OK_EXPECTED_HEX, FixtureModes.hex(
            settings.annotated.v1.SettingsPxfCodec.unmarshal(FixtureModesTest.OK_PXF).toByteArray()));
    }

    @Test
    void annotationForm_enforcesRequired() {
        PxfException e = assertThrows(PxfException.class,
            () -> settings.annotated.v1.SettingsPxfCodec.unmarshal(FixtureModesTest.MISSING_REQUIRED_PXF));
        assertEquals("1:1: required field \"name\" is absent", e.getMessage());
    }
}
