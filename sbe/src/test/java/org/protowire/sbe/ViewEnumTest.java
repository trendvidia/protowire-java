// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import org.junit.jupiter.api.Test;
import view.v1.EOuterClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link View#enumValue} reads an enum field's number, as protowire-go's
 * {@code View.Enum} does (#210): a uint8 by default, a uint16 by
 * {@code (sbe.encoding)}, and any other encoding refused with Go's text.
 */
final class ViewEnumTest {

    @Test
    void anEnumReadsAsItsNumber() {
        Codec codec = Codec.of(EOuterClass.getDescriptor());
        byte[] wire = codec.marshal(EOuterClass.E.newBuilder().setSmallValue(200).setWideValue(40000).setN(7).build());
        View v = codec.view(wire);
        assertEquals(200, v.enumValue("small"));
        assertEquals(40000, v.enumValue("wide"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> v.enumValue("n"));
        assertEquals("sbe: field n has unsupported enum encoding", e.getMessage());
    }
}
