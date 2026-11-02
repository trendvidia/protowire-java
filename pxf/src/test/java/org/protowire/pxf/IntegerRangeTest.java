// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.MapKeys;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An integer outside its field's type is an error, never a truncated value or
 * a leaked {@link NumberFormatException}, and an unsigned value prints unsigned.
 */
class IntegerRangeTest {

    private static String roundTrip(Descriptor d, String literal) {
        byte[] doc = ("value = " + literal).getBytes(StandardCharsets.UTF_8);
        DynamicMessage m = Pxf.unmarshal(doc, d);
        return new String(Pxf.marshal(m), StandardCharsets.UTF_8).trim();
    }

    @Test
    void theEndsOfEachTypeRoundTrip() {
        assertEquals("value = 2147483647", roundTrip(Int32Value.getDescriptor(), "2147483647"));
        assertEquals("value = -2147483648", roundTrip(Int32Value.getDescriptor(), "-2147483648"));
        assertEquals("value = 4294967295", roundTrip(UInt32Value.getDescriptor(), "4294967295"));
        assertEquals("value = 9223372036854775807", roundTrip(Int64Value.getDescriptor(), "9223372036854775807"));
        assertEquals("value = 18446744073709551615", roundTrip(UInt64Value.getDescriptor(), "18446744073709551615"));
    }

    @Test
    void anUnsignedMapKeyPrintsUnsigned() {
        byte[] doc = "by_uint32 = { 4000000000: \"a\" }\nby_uint64 = { 18446744073709551615: \"b\" }".getBytes(StandardCharsets.UTF_8);
        MapKeys.Builder b = MapKeys.newBuilder();
        Pxf.unmarshal(doc, b);
        String text = new String(Pxf.marshal(b.build()), StandardCharsets.UTF_8);
        assertTrue(text.contains("4000000000: \"a\""), text);
        assertTrue(text.contains("18446744073709551615: \"b\""), text);
    }

    private static void assertRefused(Descriptor d, String literal, String type) {
        PxfException e = assertThrows(PxfException.class, () -> roundTrip(d, literal), literal);
        assertTrue(e.getMessage().contains("invalid " + type + ": " + literal), e.getMessage());
    }

    @Test
    void outsideTheTypeIsRefused() {
        assertRefused(Int32Value.getDescriptor(), "2147483648", "int32");
        assertRefused(Int32Value.getDescriptor(), "5000000000", "int32");
        assertRefused(UInt32Value.getDescriptor(), "4294967296", "uint32");
        assertRefused(UInt32Value.getDescriptor(), "-1", "uint32");
        assertRefused(Int64Value.getDescriptor(), "9223372036854775808", "int64");
        assertRefused(UInt64Value.getDescriptor(), "18446744073709551616", "uint64");
        assertRefused(UInt64Value.getDescriptor(), "-1", "uint64");
    }
}
