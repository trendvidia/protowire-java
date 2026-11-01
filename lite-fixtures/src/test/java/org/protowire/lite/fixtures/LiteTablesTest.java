// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import nulls.ed.v1.EdPxfCodec;
import nulls.v1.DefaultedPxfCodec;
import nulls.v1.HolderPxfCodec;
import nulls.v1.LegacyPxfCodec;
import nulls.v1.RequiredPxfCodec;
import nulls.v1.StrictPxfCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/**
 * Tables only the plugin can fill, proved through generated companions:
 * protowire's {@code testdata/nulls/} verdicts, whose documents are inlined by
 * file name, and {@code testdata/annotations/} in the annotation form.
 */
class LiteTablesTest {
    private static Arguments row(String file, Function<String, ?> codec, String doc) {
        return Arguments.of(file, codec, doc);
    }

    static Stream<Arguments> accepted() {
        Function<String, ?> holder = HolderPxfCodec::unmarshal;
        return Stream.of(
            row("message.pxf", holder, "msg = null"),
            row("wrapper.pxf", holder, "w = null"),
            row("optional-scalar.pxf", holder, "o = null"),
            row("oneof-scalar.pxf", holder, "ks = null"),
            row("oneof-message.pxf", holder, "km = null"),
            row("proto2-scalar.pxf", LegacyPxfCodec::unmarshal, "p = null"),
            row("required-optional.pxf", RequiredPxfCodec::unmarshal, "r = null"),
            row("default-optional.pxf", DefaultedPxfCodec::unmarshal, "d = null"),
            row("editions-explicit.pxf", EdPxfCodec::unmarshal, "explicit_scalar = null"));
    }

    static Stream<Arguments> rejected() {
        Function<String, ?> holder = HolderPxfCodec::unmarshal;
        return Stream.of(
            row("err-scalar.pxf", holder, "n = null"),
            row("err-repeated.pxf", holder, "l = null"),
            row("err-list-element.pxf", holder, "l = [1, null]"),
            row("err-map.pxf", holder, "m = null"),
            row("err-map-value.pxf", holder, "m = {\n  \"a\": null\n}"),
            row("err-map-message-value.pxf", holder, "mm = {\n  \"a\": null\n}"),
            row("err-proto2-required.pxf", StrictPxfCodec::unmarshal, "r = null"),
            row("err-nested-scalar.pxf", holder, "msg { x = null }"),
            row("err-oneof-sibling.pxf", holder, "km { x = 1 }\nks = null"),
            row("err-editions-implicit.pxf", EdPxfCodec::unmarshal, "implicit_scalar = null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    void nullFixtureIsAccepted(String file, Function<String, com.google.protobuf.MessageLite> codec, String doc) {
        // Every accept fixture leaves no field set.
        assertEquals(0, codec.apply(doc).toByteArray().length, file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void nullFixtureIsRejected(String file, Function<String, ?> codec, String doc) {
        assertThrows(RuntimeException.class, () -> codec.apply(doc), file);
    }

    @Test
    void annotationForm_takesItsDefaults() {
        assertEquals(FixtureModesTest.OK_EXPECTED_HEX, FixtureModes.hex(
            settings.annotated.v1.SettingsPxfCodec.unmarshal(FixtureModesTest.OK_PXF).toByteArray()));
    }

    @Test
    void annotationForm_enforcesRequired() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> settings.annotated.v1.SettingsPxfCodec.unmarshal(FixtureModesTest.MISSING_REQUIRED_PXF));
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }
}
