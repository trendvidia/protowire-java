// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema that redefines a well-known type with the wrong fields is
 * refused at bind time, and is an ordinary message where validation does
 * not run (#202; draft -01 §wkt-shapes, protowire-go#218, protowire#487).
 * Each case's four outputs are protowire-go v1.17.0's for the same schema:
 * {@code ValidateDescriptor}, {@code Unmarshal}, {@code Unmarshal} with
 * {@code SkipValidate}, and {@code Marshal} of {@code pw} set through its
 * own first field.
 */
class WellKnownShapeTest {

    /** One wrong-shape redefinition, the document it is decoded from, Go's violation detail and Go's marshal output. */
    record Case(String pkg, String name, String field, FieldDescriptorProto.Type type, boolean repeated,
                String doc, String detail, String skipValidateError, String marshaled) {}

    private static final List<Case> CASES = List.of(
        new Case("pxf", "Secret", "other", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = \"x\"",
            "it has no field \"value\"", "1:6: expected '{' for message field \"pw\", got string (\"x\")", "pw {\n  other = \"s\"\n}\n"),
        new Case("pxf", "Secret", "value", FieldDescriptorProto.Type.TYPE_INT32, false, "pw = \"x\"",
            "field \"value\" is int32, and must be string", "1:6: expected '{' for message field \"pw\", got string (\"x\")", "pw {\n  value = 7\n}\n"),
        new Case("pxf", "Secret", "value", FieldDescriptorProto.Type.TYPE_STRING, true, "pw = \"x\"",
            "field \"value\" has the wrong cardinality", "1:6: expected '{' for message field \"pw\", got string (\"x\")",
            "pw {\n  value = [\n    \"s\"\n  ]\n}\n"),
        new Case("pxf", "BigInt", "other", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = 5",
            "it has no field \"abs\"", "1:6: expected '{' for message field \"pw\", got integer (\"5\")", "pw {\n  other = \"s\"\n}\n"),
        new Case("pxf", "BigFloat", "other", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = 1.5",
            "it has no field \"mantissa\"", "1:6: expected '{' for message field \"pw\", got float (\"1.5\")", "pw {\n  other = \"s\"\n}\n"),
        new Case("google.protobuf", "Timestamp", "other", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = 2026-01-01T00:00:00Z",
            "it has no field \"seconds\"", "1:6: expected '{' for message field \"pw\", got timestamp (\"2026-01-01T00:00:00Z\")",
            "pw {\n  other = \"s\"\n}\n"),
        new Case("google.protobuf", "Duration", "other", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = 5s",
            "it has no field \"seconds\"", "1:6: expected '{' for message field \"pw\", got duration (\"5s\")", "pw {\n  other = \"s\"\n}\n"),
        new Case("google.protobuf", "StringValue", "value", FieldDescriptorProto.Type.TYPE_INT32, false, "pw = \"x\"",
            "field \"value\" is int32, and must be string", "1:6: expected '{' for message field \"pw\", got string (\"x\")",
            "pw {\n  value = 7\n}\n"),
        new Case("google.protobuf", "Int32Value", "value", FieldDescriptorProto.Type.TYPE_STRING, false, "pw = 5",
            "field \"value\" is string, and must be int32", "1:6: expected '{' for message field \"pw\", got integer (\"5\")",
            "pw {\n  value = \"s\"\n}\n"),
        new Case("google.protobuf", "StringValue", "value", FieldDescriptorProto.Type.TYPE_STRING, true, "pw = \"x\"",
            "field \"value\" has the wrong cardinality", "1:6: expected '{' for message field \"pw\", got string (\"x\")",
            "pw {\n  value = [\n    \"s\"\n  ]\n}\n"));

    /** {@code p.proto}: the redefinition, and {@code H { <type> pw = 1; repeated <type> l = 2; }}. */
    private static Descriptor holder(String pkg, String name, FieldDescriptorProto... fields) throws Exception {
        DescriptorProto.Builder wkt = DescriptorProto.newBuilder().setName(name);
        for (FieldDescriptorProto f : fields) wkt.addField(f);
        String ref = "." + pkg + "." + name;
        DescriptorProto h = DescriptorProto.newBuilder().setName("H")
            .addField(FieldDescriptorProto.newBuilder().setName("pw").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(ref).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
            .addField(FieldDescriptorProto.newBuilder().setName("l").setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(ref).setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
            .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("p.proto").setPackage(pkg).setSyntax("proto3")
            .addMessageType(wkt).addMessageType(h).build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("H");
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type, boolean repeated) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
            .setLabel(repeated ? FieldDescriptorProto.Label.LABEL_REPEATED : FieldDescriptorProto.Label.LABEL_OPTIONAL).build();
    }

    private static Descriptor holder(Case c) throws Exception {
        return holder(c.pkg(), c.name(), field(c.field(), 1, c.type(), c.repeated()));
    }

    private static String violation(Case c) {
        return "p.proto: message \"" + c.pkg() + "." + c.name() + "\" redefines a well-known type: " + c.detail()
            + " (draft -01 §wkt-shapes)";
    }

    @Test
    void bindingRefusesAWrongShape() throws Exception {
        for (Case c : CASES) {
            List<SchemaValidator.Violation> vs = SchemaValidator.validateDescriptor(holder(c));
            assertEquals(1, vs.size(), c.toString());
            assertEquals(SchemaValidator.Kind.WELL_KNOWN_SHAPE, vs.get(0).kind(), c.toString());
            assertEquals(violation(c), vs.get(0).toString(), c.toString());

            byte[] doc = c.doc().getBytes(StandardCharsets.UTF_8);
            Descriptor h = holder(c);
            PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc, h), c.toString());
            // protowire-go's asValidationError, after the position every PxfException leads with.
            assertEquals(e.position() + ": PXF schema violations:\n  " + violation(c), e.getMessage(), c.toString());
        }
    }

    /** Where validation does not run, the message is an ordinary one, so a scalar where it is expected is Go's error. */
    @Test
    void skipValidateReadsAWrongShapeAsAnOrdinaryMessage() throws Exception {
        for (Case c : CASES) {
            Descriptor h = holder(c);
            PxfException e = assertThrows(PxfException.class, () -> UnmarshalOptions.defaults().withSkipValidate(true)
                .unmarshal(c.doc().getBytes(StandardCharsets.UTF_8), h), c.toString());
            assertEquals(c.skipValidateError(), e.getMessage(), c.toString());
        }
    }

    @Test
    void marshalWritesAWrongShapeAsAnOrdinaryBlock() throws Exception {
        for (Case c : CASES) {
            Descriptor h = holder(c);
            Descriptor w = h.findFieldByName("pw").getMessageType();
            FieldDescriptor wf = w.getFields().get(0);
            Object v = wf.getJavaType() == FieldDescriptor.JavaType.INT ? (Object) 7 : "s";
            DynamicMessage.Builder wb = DynamicMessage.newBuilder(w);
            if (wf.isRepeated()) wb.addRepeatedField(wf, v); else wb.setField(wf, v);
            DynamicMessage m = DynamicMessage.newBuilder(h).setField(h.findFieldByName("pw"), wb.build()).build();
            assertEquals(c.marshaled(), new String(Pxf.marshal(m), StandardCharsets.UTF_8), c.toString());
        }
    }

    /** A copy of a canonical schema binds: the shape is names, kinds and cardinality, not field numbers. */
    @Test
    void aCopyOfTheCanonicalShapeBinds() throws Exception {
        Descriptor copy = holder("google.protobuf", "Timestamp",
            field("nanos", 1, FieldDescriptorProto.Type.TYPE_INT32, false),
            field("seconds", 7, FieldDescriptorProto.Type.TYPE_INT64, false));
        assertEquals(List.of(), SchemaValidator.validateDescriptor(copy));
        DynamicMessage m = Pxf.unmarshal("pw = 2026-01-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), copy);
        DynamicMessage pw = (DynamicMessage) m.getField(copy.findFieldByName("pw"));
        assertEquals(1767225600L, pw.getField(pw.getDescriptorForType().findFieldByName("seconds")));

        // pxf.Secret may leave hint and fingerprint out.
        Descriptor secret = holder("pxf", "Secret", field("value", 1, FieldDescriptorProto.Type.TYPE_STRING, false));
        assertEquals(List.of(), SchemaValidator.validateDescriptor(secret));
        DynamicMessage s = Pxf.unmarshal("pw = \"x\"".getBytes(StandardCharsets.UTF_8), secret);
        assertTrue(new String(Pxf.marshal(s), StandardCharsets.UTF_8).startsWith("pw = "));

        // The canonical Timestamp, from protobuf-java, has its shape.
        assertEquals("", WellKnown.shapeProblem(Timestamp.getDescriptor()));
    }
}
