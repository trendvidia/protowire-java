// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.junit.jupiter.api.Test;
import org.protowire.check.CheckException;
import org.protowire.check.Report;
import org.protowire.check.Violation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UnmarshalOptions#withValidator} runs on the bound object after a
 * successful decode, as protowire-go's pb {@code UnmarshalOptions.Validator}
 * does (#205); the cases of encoding/pb/options_test.go.
 */
class PbValidatorTest {

    public static class Named { @ProtoField(1) String name; public Named() {} }

    private static byte[] wire() throws IOException {
        Named n = new Named();
        n.name = "x";
        return Pb.marshal(n);
    }

    @Test
    void aViolationFailsTheDecode() throws IOException {
        Report deny = new Report(List.of(new Violation("name", "test.deny", "denied")));
        List<Object> seen = new ArrayList<>();
        CheckException e = assertThrows(CheckException.class, () -> Pb.unmarshal(wire(), Named.class,
            UnmarshalOptions.defaults().withValidator(v -> { seen.add(v); return deny; })));
        assertSame(deny, e.report());
        assertEquals("x", ((Named) seen.get(0)).name);
    }

    @Test
    void aCleanPassDecodes() throws IOException {
        assertEquals("x", Pb.unmarshal(wire(), Named.class, UnmarshalOptions.defaults().withValidator(v -> null)).name);
    }

    @Test
    void anEngineErrorPropagatesAsItIs() {
        IllegalStateException boom = new IllegalStateException("engine down");
        assertSame(boom, assertThrows(IllegalStateException.class, () -> Pb.unmarshal(wire(), Named.class,
            UnmarshalOptions.defaults().withValidator(v -> { throw boom; }))));
    }

    @Test
    void theDefaultsRunNoValidator() throws IOException {
        assertNull(UnmarshalOptions.defaults().validator());
        assertEquals("x", Pb.unmarshal(wire(), Named.class).name);
        // A limit set after the validator keeps it.
        assertThrows(CheckException.class, () -> Pb.unmarshal(wire(), Named.class, UnmarshalOptions.defaults()
            .withValidator(v -> new Report(List.of(new Violation("", "", "no")))).withMaxRepeatedCount(8)));
    }
}
