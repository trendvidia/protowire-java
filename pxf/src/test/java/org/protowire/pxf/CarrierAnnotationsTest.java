// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @required} and {@code @default} in the 1327 annotation carrier
 * (#111). A compiler emits only the spelling the author wrote, so each test
 * builds the carrier's raw bytes into a field's options, and the binder must
 * answer as it does for the bracket spelling. The bytes are the wire form of
 * {@code protowire/schema/v1/descriptor.proto}'s {@code AnnotationList}.
 */
class CarrierAnnotationsTest {

    private interface Writer { void write(CodedOutputStream out) throws IOException; }

    private static byte[] wire(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(b);
        w.write(out);
        out.flush();
        return b.toByteArray();
    }

    /** Annotation{name, args} wrapped in AnnotationList{entries}. */
    private static byte[] list(String name, byte[]... args) throws IOException {
        byte[] entry = wire(o -> {
            o.writeString(1, name);
            for (byte[] a : args) o.writeByteArray(2, a);
        });
        return wire(o -> o.writeByteArray(1, entry));
    }

    private static FieldOptions options(byte[] carrier) throws IOException {
        return FieldOptions.parseFrom(wire(o -> o.writeByteArray(Annotations.EXT_ANNOTATIONS, carrier)));
    }

    private static byte[] bracketDefault(String v) throws IOException {
        return wire(o -> o.writeString(Annotations.EXT_DEFAULT, v));
    }

    private static Descriptor message(Type type, FieldOptions opts) throws Exception {
        FieldDescriptorProto f = FieldDescriptorProto.newBuilder()
                .setName("f").setNumber(1).setType(type).setLabel(Label.LABEL_OPTIONAL).setOptions(opts).build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("carrier_test.proto").setPackage("carrier.v1").setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("M").addField(f)).build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("M");
    }

    private static FieldDescriptor field(Type type, byte[] carrier) throws Exception {
        return message(type, options(carrier)).findFieldByName("f");
    }

    private static byte[] stringArg(String v) throws IOException { return wire(o -> o.writeString(10, v)); }

    private static final String REQUIRED = "protowire.schema.v1.required";
    private static final String DEFAULT = "protowire.schema.v1.default";

    @Test
    void requiredInTheCarrierIsEnforced() throws Exception {
        Descriptor d = field(Type.TYPE_STRING, list(REQUIRED)).getContainingType();
        assertTrue(Annotations.isRequired(d.findFieldByName("f")));
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.unmarshalFull(new byte[0], DynamicMessage.newBuilder(d)));
        assertTrue(e.getMessage().contains("required field \"f\" is absent"), e.getMessage());
    }

    @Test
    void defaultInTheCarrierIsApplied() throws Exception {
        FieldDescriptor f = field(Type.TYPE_STRING, list(DEFAULT, stringArg("fallback")));
        assertEquals("fallback", Annotations.getDefault(f));
        DynamicMessage.Builder b = DynamicMessage.newBuilder(f.getContainingType());
        Pxf.unmarshalFull(new byte[0], b);
        assertEquals("fallback", b.build().getField(f));
    }

    @Test
    void theNamedArgumentSpellingIsRead() throws Exception {
        byte[] named = wire(o -> { o.writeString(1, "value"); o.writeString(10, "x"); });
        assertEquals("x", Annotations.getDefault(field(Type.TYPE_STRING, list(DEFAULT, named))));
    }

    @Test
    void anUnrelatedAnnotationIsIgnored() throws Exception {
        FieldDescriptor f = field(Type.TYPE_STRING, list("protowire.schema.v1.description", stringArg("doc")));
        assertFalse(Annotations.isRequired(f));
        assertNull(Annotations.getDefault(f));
    }

    @Test
    void eachArgumentMemberReducesToTheBracketLiteral() throws Exception {
        assertEquals("-5", Annotations.getDefault(field(Type.TYPE_INT32, list(DEFAULT, wire(o -> o.writeInt64(11, -5))))));
        // No unsigned member: a large unsigned literal arrives as int64 -1.
        assertEquals("18446744073709551615",
                Annotations.getDefault(field(Type.TYPE_UINT64, list(DEFAULT, wire(o -> o.writeInt64(11, -1))))));
        assertEquals("1.5", Annotations.getDefault(field(Type.TYPE_DOUBLE, list(DEFAULT, wire(o -> o.writeDouble(12, 1.5))))));
        assertEquals("true", Annotations.getDefault(field(Type.TYPE_BOOL, list(DEFAULT, wire(o -> o.writeBool(13, true))))));
        assertEquals("AQID", Annotations.getDefault(field(Type.TYPE_BYTES,
                list(DEFAULT, wire(o -> o.writeByteArray(14, new byte[] {1, 2, 3}))))));
    }

    @Test
    void bigNumberMembersReduceToLiterals() throws Exception {
        byte[] bigInt = wire(o -> { o.writeByteArray(1, new byte[] {1, 0}); o.writeBool(2, true); });
        assertEquals("-256", Annotations.getDefault(field(Type.TYPE_STRING, list(DEFAULT, wire(o -> o.writeByteArray(16, bigInt))))));
        byte[] decimal = wire(o -> { o.writeByteArray(1, new byte[] {0x7b}); o.writeInt32(2, 2); });
        assertEquals("1.23", Annotations.getDefault(field(Type.TYPE_STRING, list(DEFAULT, wire(o -> o.writeByteArray(17, decimal))))));
        byte[] bigFloat = wire(o -> { o.writeByteArray(1, new byte[] {3}); o.writeInt32(2, -1); o.writeUInt32(3, 53); });
        assertEquals("1.5p53", Annotations.getDefault(field(Type.TYPE_STRING, list(DEFAULT, wire(o -> o.writeByteArray(18, bigFloat))))));
    }

    @Test
    void anEnumLiteralIsItsResolvedName() throws Exception {
        byte[] en = wire(o -> o.writeString(2, "STATUS_ACTIVE"));
        byte[] lit = wire(o -> o.writeByteArray(1, en));
        assertEquals("STATUS_ACTIVE", Annotations.getDefault(field(Type.TYPE_STRING, list(DEFAULT, wire(o -> o.writeByteArray(15, lit))))));
    }

    private static List<SchemaValidator.Violation> violations(Descriptor d) {
        return SchemaValidator.validateDescriptor(d);
    }

    /** The #108 rule, on every mix of spellings. */
    @Test
    void requiredWithDefaultIsAViolationInEverySpelling() throws Exception {
        byte[] both = wire(o -> {
            o.writeByteArray(1, wire(e -> e.writeString(1, REQUIRED)));
            o.writeByteArray(1, wire(e -> { e.writeString(1, DEFAULT); e.writeByteArray(2, stringArg("x")); }));
        });
        Descriptor annotated = field(Type.TYPE_STRING, both).getContainingType();
        assertEquals(SchemaValidator.Kind.REQUIRED_OPTION, violations(annotated).get(0).kind());

        FieldOptions mixed = FieldOptions.parseFrom(concat(
                wire(o -> o.writeBool(Annotations.EXT_REQUIRED, true)),
                wire(o -> o.writeByteArray(Annotations.EXT_ANNOTATIONS, list(DEFAULT, stringArg("x"))))));
        assertEquals(SchemaValidator.Kind.REQUIRED_OPTION, violations(message(Type.TYPE_STRING, mixed)).get(0).kind());
    }

    @Test
    void twoSpellingsThatDisagreeAreAViolation() throws Exception {
        FieldOptions o = FieldOptions.parseFrom(concat(bracketDefault("a"),
                wire(w -> w.writeByteArray(Annotations.EXT_ANNOTATIONS, list(DEFAULT, stringArg("b"))))));
        List<SchemaValidator.Violation> v = violations(message(Type.TYPE_STRING, o));
        assertEquals(1, v.size());
        assertEquals(SchemaValidator.Kind.DEFAULT_OPTION, v.get(0).kind());
        assertTrue(v.get(0).toString().contains("must not be reconciled"), v.get(0).toString());
    }

    @Test
    void twoSpellingsThatAgreeAreNot() throws Exception {
        FieldOptions o = FieldOptions.parseFrom(concat(bracketDefault("a"),
                wire(w -> w.writeByteArray(Annotations.EXT_ANNOTATIONS, list(DEFAULT, stringArg("a"))))));
        assertTrue(violations(message(Type.TYPE_STRING, o)).isEmpty());
    }

    @Test
    void anUnusableDefaultIsAViolationNotASilence() throws Exception {
        Descriptor d = field(Type.TYPE_STRING, list(DEFAULT)).getContainingType();
        List<SchemaValidator.Violation> v = violations(d);
        assertEquals(1, v.size());
        assertEquals(SchemaValidator.Kind.DEFAULT_OPTION, v.get(0).kind());
        assertTrue(v.get(0).toString().contains("@default carries no value argument"), v.get(0).toString());
    }

    @Test
    void aSplitCarrierMerges() throws Exception {
        FieldOptions o = FieldOptions.parseFrom(concat(
                wire(w -> w.writeByteArray(Annotations.EXT_ANNOTATIONS, list("protowire.schema.v1.description", stringArg("d")))),
                wire(w -> w.writeByteArray(Annotations.EXT_ANNOTATIONS, list(REQUIRED)))));
        assertTrue(Annotations.isRequired(message(Type.TYPE_STRING, o).findFieldByName("f")));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return ByteString.copyFrom(a).concat(ByteString.copyFrom(b)).toByteArray();
    }
}
