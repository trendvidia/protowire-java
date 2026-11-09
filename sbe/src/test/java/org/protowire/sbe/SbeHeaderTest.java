// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.DynamicMessage;
import header.a.v1.A;
import header.b.v1.B;
import header.dup.v1.Dup;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The SBE message header names its template by schema id and template id
 * together, since a template id is unique only within a schema (#196, #199;
 * draft -01 § Header and Block Length, § SBE Annotations; protowire-go#253,
 * #257). Schemas 1 and 2 each have a template 7 ({@code header/a.proto},
 * {@code header/b.proto}). Each expected text and byte string is
 * protowire-go v1.17.0's, run on the same .proto files.
 */
final class SbeHeaderTest {

    private static final Codec CODEC = Codec.of(A.getDescriptor(), B.getDescriptor());

    /** {@code A7{x: 42}} and {@code B7{y: 0x1122334455667788}}, as protowire-go writes them. */
    private static final byte[] A7 = HexFormat.of().parseHex("04000700010000002a000000");
    private static final byte[] B7 = HexFormat.of().parseHex("08000700020000008877665544332211");

    @Test
    void bothSchemasWriteGosBytes() {
        assertEquals(HexFormat.of().formatHex(A7), HexFormat.of().formatHex(CODEC.marshal(A.A7.newBuilder().setX(42).build())));
        assertEquals(HexFormat.of().formatHex(B7),
            HexFormat.of().formatHex(CODEC.marshal(B.B7.newBuilder().setY(0x1122334455667788L).build())));
    }

    @Test
    void unmarshalRefusesAnotherSchemasTemplate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> CODEC.unmarshal(B7, A.A7.newBuilder()));
        assertEquals("sbe: schema ID mismatch: got 2, want 1 for template 7", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> CODEC.unmarshalDescriptor(B7, A.A7.getDescriptor()));
        assertEquals("sbe: schema ID mismatch: got 2, want 1 for template 7", e.getMessage());
    }

    @Test
    void bindingRefusesATemplateIdTwiceInOneSchema() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Codec.of(Dup.getDescriptor()));
        assertEquals("sbe: messages header.dup.v1.X and header.dup.v1.Y share (sbe.template_id) = 5 in schema 3;"
            + " it must be unique within a schema", e.getMessage());
        // One file passed twice is the same messages, not a duplicate.
        Codec.of(A.getDescriptor(), A.getDescriptor());
    }

    @Test
    void viewFindsTheTemplateByItsSchema() {
        assertEquals(42, CODEC.view(A7).intValue("x"));
        assertEquals(0x1122334455667788L, CODEC.view(B7).intValue("y"));
        byte[] other = A7.clone();
        other[4] = 9;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CODEC.view(other));
        assertEquals("sbe: unknown template ID 7 in schema 9", e.getMessage());
    }

    /** A wire blockLength below the template's would read x from past the block (#199). */
    @Test
    void aBlockLengthBelowTheTemplatesIsRefused() {
        byte[] zero = A7.clone();
        zero[0] = 0;
        String want = "sbe: wire blockLength 0 < schema blockLength 4 for template 7";
        assertEquals(want, assertThrows(IllegalArgumentException.class, () -> CODEC.view(zero)).getMessage());
        assertEquals(want, assertThrows(IllegalArgumentException.class, () -> CODEC.unmarshal(zero, A.A7.newBuilder())).getMessage());
    }

    @Test
    void viewChecksMaxMessageSize() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> CODEC.withLimits(8, 16).view(A7));
        assertEquals("sbe: input of 12 bytes exceeds MaxMessageSize=8", e.getMessage());
    }

    @Test
    void shortInputSaysHowShort() {
        assertEquals("sbe: data too short for header: 5 bytes", assertThrows(IllegalArgumentException.class,
            () -> CODEC.unmarshal(Arrays.copyOf(A7, 5), A.A7.newBuilder())).getMessage());
        assertEquals("sbe: data too short for root block: need 12, have 10", assertThrows(IllegalArgumentException.class,
            () -> CODEC.unmarshal(Arrays.copyOf(A7, 10), A.A7.newBuilder())).getMessage());
        // The decoded message itself, for completeness.
        DynamicMessage m = CODEC.unmarshalDescriptor(A7, A.A7.getDescriptor());
        assertEquals(42, m.getField(A.A7.getDescriptor().findFieldByName("x")));
    }
}
