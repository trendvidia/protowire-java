// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DurationProto;
import com.google.protobuf.TimestampProto;
import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.Bignum;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A value of the wrong kind on a message field says which values the field
 * takes and what it got, in protowire-go's words ({@code msgValueForms} and
 * {@code tokenErrMsg}). Found while holding a wrong-shape well-known type to
 * Go's output (#202): once it is an ordinary message, a scalar on it reaches
 * this error.
 */
class MessageFieldValueFormsTest {

    /** {@code forms.v1.F}: one field of each well-known message type that takes a literal, and one ordinary message. */
    private static Descriptor forms() throws Exception {
        DescriptorProto inner = DescriptorProto.newBuilder().setName("Inner")
            .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(1).setType(FieldDescriptorProto.Type.TYPE_INT32))
            .build();
        DescriptorProto.Builder f = DescriptorProto.newBuilder().setName("F");
        String[][] fields = {
            {"ts", ".google.protobuf.Timestamp"}, {"d", ".google.protobuf.Duration"}, {"bi", ".pxf.BigInt"},
            {"dec", ".pxf.Decimal"}, {"bf", ".pxf.BigFloat"}, {"m", ".forms.v1.Inner"}};
        for (int i = 0; i < fields.length; i++) {
            f.addField(FieldDescriptorProto.newBuilder().setName(fields[i][0]).setNumber(i + 1)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(fields[i][1]));
        }
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("f.proto").setPackage("forms.v1").setSyntax("proto3")
            .addDependency("google/protobuf/timestamp.proto").addDependency("google/protobuf/duration.proto")
            .addDependency(Bignum.getDescriptor().getName())
            .addMessageType(inner).addMessageType(f).build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[] {
            TimestampProto.getDescriptor(), DurationProto.getDescriptor(), Bignum.getDescriptor()}).findMessageTypeByName("F");
    }

    /** protowire-go v1.17.0's Unmarshal error for each document, on the same schema. */
    private static final Map<String, String> GO = Map.ofEntries(
        Map.entry("ts = 5", "1:6: expected a timestamp literal or '{' for message field \"ts\", got integer (\"5\")"),
        Map.entry("ts = \"x\"", "1:6: expected a timestamp literal or '{' for message field \"ts\", got string (\"x\")"),
        Map.entry("d = \"x\"", "1:5: expected a duration literal or '{' for message field \"d\", got string (\"x\")"),
        Map.entry("d = 5", "1:5: expected a duration literal or '{' for message field \"d\", got integer (\"5\")"),
        Map.entry("bi = \"x\"", "1:6: expected an integer literal or '{' for message field \"bi\", got string (\"x\")"),
        Map.entry("bi = 1.5", "1:6: expected an integer literal or '{' for message field \"bi\", got float (\"1.5\")"),
        Map.entry("dec = \"x\"", "1:7: expected a numeric literal or '{' for message field \"dec\", got string (\"x\")"),
        Map.entry("bf = true", "1:6: expected a numeric literal or '{' for message field \"bf\", got bool (\"true\")"),
        Map.entry("m = 5", "1:5: expected '{' for message field \"m\", got integer (\"5\")"),
        Map.entry("m = \"x\"", "1:5: expected '{' for message field \"m\", got string (\"x\")"),
        Map.entry("m = [1]", "1:5: expected '{' for message field \"m\", got [ (\"[\")"));

    @Test
    void aWrongValueOnAMessageFieldIsGosError() throws Exception {
        Descriptor f = forms();
        GO.forEach((doc, want) -> {
            PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), f), doc);
            assertEquals(want, e.getMessage(), doc);
        });
    }
}
