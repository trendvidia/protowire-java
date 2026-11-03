// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import nulls.v1.Legacy;
import nulls.v1.LegacyPxfMeta;
import nulls.v1.Strict;
import nulls.v1.StrictPxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Parser;
import org.protowire.pxf.android.LiteWireWriter;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LiteWireWriter leaves out a zero only on a field with no presence (#156).
 * A proto2 field has presence, required or not, so its zero is written, as
 * protobuf writes it; dropping a required one would make the bytes
 * unreadable. NULLABLE_FIELDS cannot tell a required field from a proto3
 * plain one, which is why the plugin emits IMPLICIT_PRESENCE_FIELDS.
 */
final class ZeroPresenceTest {

    @Test
    void aProto2RequiredZeroIsWritten() throws Exception {
        byte[] bytes = LiteWireWriter.encode(Parser.parse("r = 0"), StrictPxfMeta.INSTANCE);
        assertEquals("0800", HexFormat.of().formatHex(bytes));
        assertTrue(Strict.parseFrom(bytes).hasR());
    }

    @Test
    void aProto2OptionalZeroIsWritten() throws Exception {
        byte[] bytes = LiteWireWriter.encode(Parser.parse("p = 0"), LegacyPxfMeta.INSTANCE);
        assertEquals("0800", HexFormat.of().formatHex(bytes));
        assertTrue(Legacy.parseFrom(bytes).hasP());
    }
}
