// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;
import wkt.test.v1.GoogleMessages;
import wkt.test.v1.GoogleMessagesPxfCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lite schema with a field of each google/protobuf type that PXF reads as an
 * ordinary message compiles, and each round-trips through the generated codec
 * with no registry (trendvidia/protowire#539). The lite tier has no type
 * resolver, so an Any is written as {@code type_url} and {@code value}, which
 * protowire#501 allows: the bytes read back are the stored bytes.
 */
final class GoogleMessagesLiteTest {

    private static GoogleMessages roundTrip(GoogleMessages in) {
        String text = GoogleMessagesPxfCodec.marshal(in);
        GoogleMessages back = GoogleMessagesPxfCodec.unmarshal(text);
        assertEquals(in, back, text);
        return back;
    }

    /**
     * Bytes no body could reproduce: an unknown field 99, then field 1 twice.
     * Read back as anything but these bytes, the Any has changed.
     */
    private static final ByteString ODD_VALUE = ByteString.copyFrom(new byte[] {
        (byte) 0x98, 0x06, 0x01, 0x08, 0x01, 0x08, 0x02,
    });

    @Test
    void anAnyKeepsItsTypeUrlAndValueBytes() {
        Any any = Any.newBuilder().setTypeUrl("type.googleapis.com/x.v1.Thing").setValue(ODD_VALUE).build();
        GoogleMessages back = roundTrip(GoogleMessages.newBuilder()
                .setAny(any)
                .addAnys(any)
                .addAnys(Any.getDefaultInstance())
                .putAnyMap("k", any)
                .build());
        assertEquals(ODD_VALUE, back.getAny().getValue());
        assertEquals(ODD_VALUE, back.getAnys(0).getValue());
        assertEquals(ODD_VALUE, back.getAnyMapOrThrow("k").getValue());
    }

    @Test
    void anAnyIsWrittenAsItsTwoFields() {
        String text = GoogleMessagesPxfCodec.marshal(GoogleMessages.newBuilder()
                .setAny(Any.newBuilder().setTypeUrl("t/x").setValue(ODD_VALUE))
                .build());
        assertTrue(text.contains("type_url = \"t/x\""), text);
        assertTrue(text.contains("value = b\""), text);
    }

    @Test
    void emptyAndFieldMask() {
        roundTrip(GoogleMessages.newBuilder()
                .setEmpty(Empty.getDefaultInstance())
                .setMask(FieldMask.newBuilder().addPaths("a.b").addPaths("c"))
                .build());
    }

    @Test
    void structValueAndListValue() {
        Value nested = Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setNumberValue(1.5))
                .addValues(Value.newBuilder().setStringValue("s"))
                .addValues(Value.newBuilder().setBoolValue(true))
                .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
                .build();
        Struct struct = Struct.newBuilder()
                .putFields("n", Value.newBuilder().setNumberValue(2).build())
                .putFields("l", nested)
                .putFields("s", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("inner", Value.newBuilder().setStringValue("x").build())).build())
                .build();
        roundTrip(GoogleMessages.newBuilder()
                .setStruct(struct)
                .setValue(Value.newBuilder().setStructValue(struct))
                .setList(nested.getListValue())
                .build());
    }

    @Test
    void aNullValueEnumField() {
        // NULL_VALUE is 0, a plain enum's zero, so it is not written; the
        // field reads back as its zero either way.
        roundTrip(GoogleMessages.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
        // A Value whose kind is null_value has presence, so it is written.
        GoogleMessages back = roundTrip(GoogleMessages.newBuilder()
                .setValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)).build());
        assertEquals(Value.KindCase.NULL_VALUE, back.getValue().getKindCase());
    }
}
