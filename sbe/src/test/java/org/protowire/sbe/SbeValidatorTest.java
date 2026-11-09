// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import org.junit.jupiter.api.Test;
import org.protowire.check.CheckException;
import org.protowire.check.Report;
import org.protowire.check.Violation;
import org.protowire.sbe.testproto.NewOrderSingle;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Codec#withValidator} runs after a successful decode, as
 * protowire-go's {@code CodecOptions.Validator} does (#205); the cases of
 * encoding/sbe/validator_test.go but its stream one, since Java has no SBE
 * stream decoder yet (#207). {@link Codec#view} reads without a message, so
 * it does not validate.
 */
final class SbeValidatorTest {

    private static final Codec CODEC = Codec.of(NewOrderSingle.getDescriptor().getFile());
    private static final byte[] WIRE = CODEC.marshal(NewOrderSingle.newBuilder().setOrderId(1).setSymbol("AAPL").build());
    private static final Report DENY = new Report(List.of(new Violation("symbol", "test.deny", "denied")));

    @Test
    void aViolationFailsTheDecode() {
        List<Object> seen = new ArrayList<>();
        Codec c = CODEC.withValidator(v -> { seen.add(v); return DENY; });
        assertSame(DENY, assertThrows(CheckException.class, () -> c.unmarshal(WIRE, NewOrderSingle.newBuilder())).report());
        assertEquals("AAPL", ((NewOrderSingle) seen.get(0)).getSymbol());
        assertSame(DENY, assertThrows(CheckException.class,
            () -> c.unmarshalDescriptor(WIRE, NewOrderSingle.getDescriptor())).report());
        // view reads the buffer without a message.
        assertEquals(1, c.view(WIRE).uintValue("order_id"));
    }

    @Test
    void aCleanPassDecodes() {
        NewOrderSingle.Builder b = NewOrderSingle.newBuilder();
        CODEC.withValidator(v -> null).unmarshal(WIRE, b);
        assertEquals("AAPL", b.getSymbol());
    }

    @Test
    void anEngineErrorPropagatesAsItIs() {
        IllegalStateException boom = new IllegalStateException("engine down");
        assertSame(boom, assertThrows(IllegalStateException.class,
            () -> CODEC.withValidator(v -> { throw boom; }).unmarshal(WIRE, NewOrderSingle.newBuilder())));
    }

    @Test
    void limitsSetAfterKeepTheValidator() {
        Codec c = CODEC.withValidator(v -> DENY).withLimits(1 << 20, 64);
        assertThrows(CheckException.class, () -> c.unmarshal(WIRE, NewOrderSingle.newBuilder()));
    }
}
