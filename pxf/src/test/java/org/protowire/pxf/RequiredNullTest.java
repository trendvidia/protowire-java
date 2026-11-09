// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int32Value;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A required field holds a value, so {@code null} on it is rejected as
 * absence is (protowire#569, #189), whichever spelling marks it required and
 * whatever its type or presence: in a body, a nested body, a {@code @dataset}
 * cell and a dotted cell, by {@code unmarshal} as well as
 * {@code unmarshalFull}. Mirrors protowire-go's TestRequiredRejectsNull, whose
 * schema this builds at runtime: a compiler emits only the spelling the author
 * wrote, so the {@code @required} carrier's bytes are written out by hand.
 */
class RequiredNullTest {

    private static final List<String> FIELDS = List.of("r", "a", "w", "s", "plain");
    /** The same literal serves as a body value and as a cell. */
    private static final Map<String, String> VALUES = Map.of("r", "0", "a", "0", "w", "0", "s", "{}", "plain", "0");

    private static final Descriptor REQ;
    private static final Descriptor OUTER;

    static {
        try {
            FieldOptions bracket = FieldOptions.parseFrom(wire(o -> o.writeBool(Annotations.EXT_REQUIRED, true)));
            FieldOptions carrier = FieldOptions.parseFrom(wire(o -> o.writeByteArray(Annotations.EXT_ANNOTATIONS,
                    wire(l -> l.writeByteArray(1, wire(a -> a.writeString(1, "protowire.schema.v1.required")))))));
            DescriptorProto req = DescriptorProto.newBuilder().setName("Req")
                    // optional int32 r = 1 [(pxf.required) = true];
                    .addField(field("r", 1, Type.TYPE_INT32, null, bracket).setProto3Optional(true).setOneofIndex(0))
                    // optional int32 a = 2 @required;
                    .addField(field("a", 2, Type.TYPE_INT32, null, carrier).setProto3Optional(true).setOneofIndex(1))
                    // google.protobuf.Int32Value w = 3 [(pxf.required) = true];
                    .addField(field("w", 3, Type.TYPE_MESSAGE, ".google.protobuf.Int32Value", bracket))
                    // Sub s = 4 @required;
                    .addField(field("s", 4, Type.TYPE_MESSAGE, ".annot.v1.Sub", carrier))
                    // int32 plain = 5 [(pxf.required) = true];
                    .addField(field("plain", 5, Type.TYPE_INT32, null, bracket))
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_r"))
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_a"))
                    .build();
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("required_null.proto").setPackage("annot.v1").setSyntax("proto3")
                    .addDependency("google/protobuf/wrappers.proto")
                    .addMessageType(DescriptorProto.newBuilder().setName("Sub")
                            .addField(field("x", 1, Type.TYPE_INT32, null, FieldOptions.getDefaultInstance())))
                    .addMessageType(req)
                    .addMessageType(DescriptorProto.newBuilder().setName("Outer")
                            .addField(field("in", 1, Type.TYPE_MESSAGE, ".annot.v1.Req", FieldOptions.getDefaultInstance())))
                    .build();
            FileDescriptor fd = FileDescriptor.buildFrom(file,
                    new FileDescriptor[] {Int32Value.getDescriptor().getFile()});
            REQ = fd.findMessageTypeByName("Req");
            OUTER = fd.findMessageTypeByName("Outer");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private interface Writer { void write(CodedOutputStream out) throws IOException; }

    private static byte[] wire(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(b);
        w.write(out);
        out.flush();
        return b.toByteArray();
    }

    private static FieldDescriptorProto.Builder field(String name, int num, Type type, String typeName, FieldOptions opts) {
        FieldDescriptorProto.Builder f = FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(num).setType(type).setLabel(Label.LABEL_OPTIONAL).setOptions(opts);
        if (typeName != null) f.setTypeName(typeName);
        return f;
    }

    /** One entry per field, {@code null} on {@code nulled} (none when empty). */
    private static String body(String nulled) {
        StringBuilder b = new StringBuilder();
        for (String f : FIELDS) b.append(f).append(" = ").append(f.equals(nulled) ? "null" : VALUES.get(f)).append('\n');
        return b.toString();
    }

    private static String row(String nulled) {
        List<String> cells = new ArrayList<>();
        for (String f : FIELDS) cells.add(f.equals(nulled) ? "null" : VALUES.get(f));
        return "(" + String.join(", ", cells) + ")\n";
    }

    private static String dataset(String type, boolean dotted, String nulled) {
        List<String> cols = FIELDS.stream().map(f -> dotted ? "in." + f : f).toList();
        return "@dataset annot.v1." + type + " (" + String.join(", ", cols) + ")\n" + row(nulled);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static void bindRows(Descriptor d, String doc) {
        for (Ast.DatasetDirective t : Pxf.parse(utf8(doc)).datasets()) {
            for (Ast.DatasetRow r : t.rows()) BindRow.bindRow(DynamicMessage.newBuilder(d), t.columns(), r);
        }
    }

    private static void rejects(String want, Executable call) {
        PxfException e = assertThrows(PxfException.class, call);
        assertTrue(e.getMessage().contains(want), e.getMessage());
    }

    @Test
    void zeroValuesSatisfyEveryRequiredField() {
        assertDoesNotThrow(() -> Pxf.unmarshal(utf8(body("")), REQ));
        assertDoesNotThrow(() -> Pxf.unmarshalFull(utf8(body("")), DynamicMessage.newBuilder(REQ)));
        assertDoesNotThrow(() -> Pxf.unmarshalFull(utf8("in {\n" + body("") + "}\n"), DynamicMessage.newBuilder(OUTER)));
        assertDoesNotThrow(() -> bindRows(REQ, dataset("Req", false, "")));
        assertDoesNotThrow(() -> bindRows(OUTER, dataset("Outer", true, "")));
    }

    @TestFactory
    List<DynamicTest> nullOnARequiredFieldIsRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String f : FIELDS) {
            String want = "null is not allowed on required field \"" + f + "\": a required field holds a value";
            tests.add(DynamicTest.dynamicTest(f, () -> {
                // At the null literal: line of the entry, past "<name> = ".
                String at = (FIELDS.indexOf(f) + 1) + ":" + (f.length() + 4) + ": ";
                rejects(at + want, () -> Pxf.unmarshal(utf8(body(f)), REQ));
                rejects(want, () -> Pxf.unmarshalFull(utf8(body(f)), DynamicMessage.newBuilder(REQ)));
                rejects(want, () -> Pxf.unmarshalFull(utf8("in {\n" + body(f) + "}\n"), DynamicMessage.newBuilder(OUTER)));
                rejects("2:1: column " + f + ": " + want, () -> bindRows(REQ, dataset("Req", false, f)));
                rejects(want, () -> bindRows(OUTER, dataset("Outer", true, f)));
            }));
        }
        return tests;
    }
}
