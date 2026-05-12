// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reserved-name schema check (draft §3.13). Mirrors the Go-port tests in
 * encoding/pxf/schema_test.go.
 *
 * <p>The protowire-java test fixtures don't include schemas with names
 * colliding with PXF keywords (and protoc's Java code generator rejects
 * some collisions like {@code field "true"} via language-target keyword
 * checks). To build adversarial schemas without those constraints, we
 * construct {@link FileDescriptorProto} values directly and materialize
 * a {@link FileDescriptor} via {@link FileDescriptor#buildFrom}.
 */
class SchemaValidatorTest {

    @Test
    void cleanSchemaProducesNoViolations() {
        // The AllTypes fixture has no reserved-name violations.
        List<SchemaValidator.Violation> v =
                SchemaValidator.validateDescriptor(AllTypes.getDescriptor());
        assertEquals(List.of(), v);
    }

    @Test
    void rejectsReservedEnumValue() {
        FileDescriptor fd = buildFile(
                "trades.proto", "trades.v1",
                m("Order",
                        f("side", 1, Type.TYPE_ENUM, "Side")),
                e("Side",
                        ev("SIDE_UNSPECIFIED", 0),
                        ev("BUY", 1),
                        ev("null", 2)));

        var v = SchemaValidator.validateFile(fd);
        assertEquals(1, v.size());
        assertEquals(SchemaValidator.Kind.ENUM_VALUE, v.get(0).kind());
        // protobuf-java scopes enum values under their enum
        // (`trades.v1.Side.null`); Go's protoreflect lifts them to the
        // enclosing package (`trades.v1.null`). Both are valid readings of
        // the proto namespace rules; the rule we test is "the value's
        // bare name collides with a PXF keyword."
        assertEquals("trades.v1.Side.null", v.get(0).element());
        assertEquals("null", v.get(0).name());
    }

    @Test
    void rejectsReservedFieldName() {
        FileDescriptor fd = buildFile(
                "flag.proto", "flag.v1",
                m("Flag",
                        f("enabled", 1, Type.TYPE_BOOL, null),
                        f("true", 2, Type.TYPE_BOOL, null)));

        var v = SchemaValidator.validateFile(fd);
        assertEquals(1, v.size());
        assertEquals(SchemaValidator.Kind.FIELD, v.get(0).kind());
        assertEquals("flag.v1.Flag.true", v.get(0).element());
    }

    @Test
    void rejectsReservedOneofName() {
        DescriptorProto.Builder choice = DescriptorProto.newBuilder().setName("Choice");
        choice.addOneofDecl(OneofDescriptorProto.newBuilder().setName("false"));
        choice.addField(FieldDescriptorProto.newBuilder()
                .setName("text").setNumber(1)
                .setType(Type.TYPE_STRING).setLabel(Label.LABEL_OPTIONAL)
                .setOneofIndex(0));
        FileDescriptor fd = buildFile("choice.proto", "choice.v1", choice.build());

        var v = SchemaValidator.validateFile(fd);
        assertEquals(1, v.size());
        assertEquals(SchemaValidator.Kind.ONEOF, v.get(0).kind());
        assertEquals("choice.v1.Choice.false", v.get(0).element());
    }

    @Test
    void caseSensitive_acceptsUppercase() {
        FileDescriptor fd = buildFile(
                "box.proto", "box.v1",
                m("Box",
                        f("NULL", 1, Type.TYPE_STRING, null),
                        f("True", 2, Type.TYPE_BOOL, null)),
                e("Truth",
                        ev("TRUTH_UNSPECIFIED", 0),
                        ev("NULL", 1),
                        ev("TRUE", 2),
                        ev("FALSE", 3)));

        assertEquals(List.of(), SchemaValidator.validateFile(fd));
    }

    @Test
    void nestedMessageReservedField() {
        DescriptorProto inner = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(f("false", 1, Type.TYPE_BOOL, null))
                .build();
        DescriptorProto outer = DescriptorProto.newBuilder()
                .setName("Outer")
                .addNestedType(inner)
                .build();
        FileDescriptor fd = buildFile("nest.proto", "nest.v1", outer);

        var v = SchemaValidator.validateFile(fd);
        assertEquals(1, v.size());
        assertEquals("nest.v1.Outer.Inner.false", v.get(0).element());
    }

    @Test
    void violationsSortedByElementName() {
        FileDescriptor fd = buildFile(
                "multi.proto", "m.v1",
                DescriptorProto.newBuilder()
                        .setName("Z")
                        .addField(f("true", 1, Type.TYPE_BOOL, null))
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("false"))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("s").setNumber(2)
                                .setType(Type.TYPE_STRING).setLabel(Label.LABEL_OPTIONAL)
                                .setOneofIndex(0))
                        .build());

        var v = SchemaValidator.validateFile(fd);
        assertEquals(2, v.size());
        // Sorted by element FQN.
        assertTrue(v.get(0).element().compareTo(v.get(1).element()) < 0);
    }

    @Test
    void asMessage_emptyReturnsNull() {
        assertNull(SchemaValidator.asMessage(List.of()));
    }

    @Test
    void asMessage_includesDraftReference() {
        var msg = SchemaValidator.asMessage(List.of(
                new SchemaValidator.Violation(
                        "t.proto", "p.v1.M.null", "null", SchemaValidator.Kind.FIELD)));
        assertTrue(msg.contains("§3.13"));
        assertTrue(msg.contains("p.v1.M.null"));
    }

    @Test
    void validateDescriptor_nullReturnsEmpty() {
        assertEquals(List.of(), SchemaValidator.validateDescriptor(null));
    }

    @Test
    void validateFile_nullReturnsEmpty() {
        assertEquals(List.of(), SchemaValidator.validateFile(null));
    }

    // --- Wiring: FastDecoder rejects on a non-conforming descriptor ---

    @Test
    void unmarshal_defaultRejectsNonConformantSchema() throws Descriptors.DescriptorValidationException {
        FileDescriptor fd = buildFile(
                "bad.proto", "bad.v1",
                m("M", f("true", 1, Type.TYPE_BOOL, null)));
        var desc = fd.findMessageTypeByName("M");
        var b = DynamicMessage.newBuilder(desc);
        var ex = assertThrows(PxfException.class,
                () -> UnmarshalOptions.defaults().unmarshal("true = true".getBytes(), b));
        assertTrue(ex.getMessage().contains("reserved-name"),
                () -> "expected reserved-name violation in: " + ex.getMessage());
    }

    @Test
    void unmarshal_skipValidateBypasses() throws Descriptors.DescriptorValidationException {
        // Use a clean schema; SkipValidate just exercises the no-validation
        // path on the happy case.
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        try {
            UnmarshalOptions.defaults().withSkipValidate(true)
                    .unmarshal("string_field = \"x\"".getBytes(), b);
        } catch (PxfException ex) {
            fail("SkipValidate happy-path should not raise: " + ex.getMessage());
        }
    }

    // --- Helpers --------------------------------------------------------

    /** Build a FileDescriptor with the given message + enum descriptors. */
    private static FileDescriptor buildFile(String filename, String pkg, Object... members) {
        FileDescriptorProto.Builder fdp = FileDescriptorProto.newBuilder()
                .setName(filename)
                .setPackage(pkg)
                .setSyntax("proto3");
        for (Object m : members) {
            if (m instanceof DescriptorProto d) fdp.addMessageType(d);
            else if (m instanceof EnumDescriptorProto e) fdp.addEnumType(e);
            else throw new IllegalArgumentException("unknown member type: " + m.getClass());
        }
        try {
            return FileDescriptor.buildFrom(fdp.build(), new FileDescriptor[0]);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Build a message descriptor proto with the given fields. */
    private static DescriptorProto m(String name, Object... fields) {
        DescriptorProto.Builder b = DescriptorProto.newBuilder().setName(name);
        for (Object f : fields) {
            if (f instanceof FieldDescriptorProto fd) b.addField(fd);
            else throw new IllegalArgumentException("expected field, got " + f.getClass());
        }
        return b.build();
    }

    /** Build an enum descriptor proto with the given values. */
    private static EnumDescriptorProto e(String name, EnumValueDescriptorProto... values) {
        EnumDescriptorProto.Builder b = EnumDescriptorProto.newBuilder().setName(name);
        for (EnumValueDescriptorProto v : values) b.addValue(v);
        return b.build();
    }

    /** Build a field descriptor proto. */
    private static FieldDescriptorProto f(String name, int number, Type type, String typeName) {
        var b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(Label.LABEL_OPTIONAL);
        if (typeName != null) b.setTypeName(typeName);
        return b.build();
    }

    /** Build an enum-value descriptor proto. */
    private static EnumValueDescriptorProto ev(String name, int number) {
        return EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number).build();
    }
}
