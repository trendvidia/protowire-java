// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import mapkeys.v1.BoolKeys.Flags;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/map-keys/} bool-key fixtures
 * (vendored under {@code src/test/resources/map-keys/} from
 * trendvidia/protowire a0dd520, 2026-09-08; the README there states the
 * verdicts). Three documents MUST bind to the keys true and false;
 * every file under {@code invalid/} MUST NOT bind, with an error naming
 * the key.
 */
class MapKeysFixtureTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MapKeysFixtureTest.class.getResourceAsStream("/map-keys/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static Flags decode(String name) throws IOException {
        Flags.Builder b = Flags.newBuilder();
        Pxf.unmarshal(fixture(name), b);
        return b.build();
    }

    @Test
    void mustBind() throws IOException {
        for (String name : List.of("bool-keys.pxf", "bool-keys-keyword.pxf", "bool-keys-integer.pxf")) {
            Flags f = decode(name);
            assertEquals(2, f.getByFlagCount(), name);
            assertTrue(f.getByFlagMap().containsKey(true), name);
            assertTrue(f.getByFlagMap().containsKey(false), name);
        }
    }

    // The invalid/ directory, by name: each carries the offending key in
    // its file name after the last '-'.
    private static final List<String> INVALID = List.of(
            "identifier-lower-f", "identifier-lower-t", "identifier-title-False", "identifier-title-True",
            "identifier-upper-F", "identifier-upper-FALSE", "identifier-upper-T", "identifier-upper-TRUE",
            "identifier-word-yes", "string-lower-t", "string-one-1", "string-upper-TRUE", "string-zero-0");

    @Test
    void mustNotBind() {
        for (String stem : INVALID) {
            String key = stem.substring(stem.lastIndexOf('-') + 1);
            PxfException e = assertThrows(PxfException.class, () -> decode("invalid/" + stem + ".pxf"), stem);
            assertTrue(e.getMessage().contains("invalid bool map key"), stem + " -> " + e.getMessage());
            assertTrue(e.getMessage().contains(key), stem + " must name the key: " + e.getMessage());
            assertTrue(e.getMessage().contains("\"by_flag\""), stem + " must name the field: " + e.getMessage());
        }
    }
}
