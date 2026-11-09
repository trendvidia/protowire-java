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
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DatasetReader.scan binds a row under the options the reader was built
 * with, as BindRow.bindRow binds it, except MaxMessageSize (#194,
 * protowire-go#280). Each case binds one row through both paths, with the
 * option off and on, and requires the two to agree. The cases are
 * protowire-go's dataset_scan_options_test.go for the options this port has
 * (it has no secret hook or validator yet, #205, #206), plus MaxMessageSize.
 */
class DatasetScanOptionsTest {

    private interface Writer { void write(CodedOutputStream out) throws IOException; }

    private static byte[] wire(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(b);
        w.write(out);
        out.flush();
        return b.toByteArray();
    }

    private static FieldDescriptorProto.Builder field(String name, int num, Type type, String typeName) {
        FieldDescriptorProto.Builder f = FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(num).setType(type).setLabel(Label.LABEL_OPTIONAL);
        if (typeName != null) f.setTypeName(typeName);
        return f;
    }

    private static final Descriptor ROW;
    private static final Descriptor BAD;
    static {
        try {
            FieldOptions required = FieldOptions.parseFrom(wire(o -> o.writeBool(Annotations.EXT_REQUIRED, true)));
            FieldOptions dflt = FieldOptions.parseFrom(wire(o -> o.writeString(Annotations.EXT_DEFAULT, "z")));
            FileDescriptorProto so = FileDescriptorProto.newBuilder()
                    .setName("so.proto").setPackage("so.v1").setSyntax("proto3")
                    .addMessageType(DescriptorProto.newBuilder().setName("Inner").addField(field("n", 1, Type.TYPE_INT32, null)))
                    .addMessageType(DescriptorProto.newBuilder().setName("Row")
                            .addField(field("id", 1, Type.TYPE_STRING, null))
                            .addField(field("req", 2, Type.TYPE_INT32, null).setOptions(required))
                            .addField(field("dflt", 3, Type.TYPE_STRING, null).setOptions(dflt))
                            .addField(field("inner", 4, Type.TYPE_MESSAGE, ".so.v1.Inner")))
                    .build();
            // A field named null breaks a bind-time schema rule (draft §3.13).
            FileDescriptorProto sobad = FileDescriptorProto.newBuilder()
                    .setName("sobad.proto").setPackage("sobad.v1").setSyntax("proto3")
                    .addMessageType(DescriptorProto.newBuilder().setName("Bad")
                            .addField(field("id", 1, Type.TYPE_STRING, null))
                            .addField(field("null", 2, Type.TYPE_STRING, null)))
                    .build();
            ROW = FileDescriptor.buildFrom(so, new FileDescriptor[0]).findMessageTypeByName("Row");
            BAD = FileDescriptor.buildFrom(sobad, new FileDescriptor[0]).findMessageTypeByName("Bad");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Binds the one row of {@code table} under {@code o} through BindRow and
     * through DatasetReader.scan, requires the two to agree, and returns the
     * message, or the error text (as {@code "error: ..."} in {@link Bound}).
     */
    private record Bound(Message message, String error) {}

    private static Bound bindUnder(UnmarshalOptions o, Descriptor d, String table) throws IOException {
        byte[] bytes = table.getBytes(StandardCharsets.UTF_8);
        Ast.DatasetDirective ds = Pxf.parse(bytes).datasets().get(0);
        assertEquals(1, ds.rows().size());
        DynamicMessage.Builder viaBind = DynamicMessage.newBuilder(d);
        String bindErr = null;
        try {
            BindRow.bindRow(o, viaBind, ds.columns(), ds.rows().get(0));
        } catch (PxfException e) {
            bindErr = e.getMessage();
        }
        DatasetReader r = new DatasetReader(new ByteArrayInputStream(bytes), o);
        DynamicMessage.Builder viaScan = DynamicMessage.newBuilder(d);
        String scanErr = null;
        try {
            assertTrue(r.scan(viaScan));
        } catch (PxfException e) {
            scanErr = e.getMessage();
        }
        assertEquals(bindErr, scanErr, "BindRow vs scan");
        if (bindErr != null) return new Bound(null, bindErr);
        assertEquals(viaBind.build(), viaScan.build(), "BindRow vs scan");
        assertFalse(r.scan(DynamicMessage.newBuilder(d)), "one row");
        return new Bound(viaScan.build(), null);
    }

    @Test
    void skipPostDecode() throws IOException {
        String table = "@dataset so.v1.Row ( id )\n(\"x\")\n";
        assertEquals("2:1: required field \"req\" is absent", bindUnder(UnmarshalOptions.defaults(), ROW, table).error());

        Bound b = bindUnder(UnmarshalOptions.defaults().withSkipPostDecode(true), ROW, table);
        assertNull(b.error());
        // No post-decode pass: the required field is not checked, and the default is not applied.
        assertEquals("", b.message().getField(ROW.findFieldByName("dflt")));
    }

    @Test
    void discardUnknown() throws IOException {
        String table = "@dataset so.v1.Row ( id, req, inner )\n(\"x\", 1, { n = 2 nope = 3 })\n";
        assertEquals("2:1: column inner: unknown field \"nope\" in so.v1.Inner",
                bindUnder(UnmarshalOptions.defaults(), ROW, table).error());

        Bound b = bindUnder(UnmarshalOptions.defaults().withDiscardUnknown(true), ROW, table);
        assertNull(b.error());
        Message inner = (Message) b.message().getField(ROW.findFieldByName("inner"));
        assertEquals(2, inner.getField(inner.getDescriptorForType().findFieldByName("n")));
    }

    /** A schema violation comes back as it is, not as the row's: only a decode error is moved to the row. */
    @Test
    void skipValidate() throws IOException {
        String table = "@dataset sobad.v1.Bad ( id )\n(\"x\")\n";
        String err = bindUnder(UnmarshalOptions.defaults(), BAD, table).error();
        assertTrue(err != null && err.contains("PXF schema violations:")
                && err.contains("message field \"sobad.v1.Bad.null\" uses PXF-reserved name \"null\""), err);
        assertFalse(err.startsWith("2:1:"), err);

        Bound b = bindUnder(UnmarshalOptions.defaults().withSkipValidate(true), BAD, table);
        assertNull(b.error());
        assertEquals("x", b.message().getField(BAD.findFieldByName("id")));
    }

    /** The column paths are checked before the schema: a row type that breaks both gets the column's error. */
    @Test
    void columnPathsBeforeSchema() throws IOException {
        assertEquals("2:1: @dataset column \"nope\": sobad.v1.Bad has no field \"nope\"",
                bindUnder(UnmarshalOptions.defaults(), BAD, "@dataset sobad.v1.Bad ( nope )\n(\"x\")\n").error());
    }

    /** The row's body repeats the column names, so it is wider than the row: it is bound at the default MaxMessageSize. */
    @Test
    void maxMessageSizeIsNotTheRowBodys() throws IOException {
        String table = "@dataset so.v1.Row ( id, req )\n(\"x\", 1)\n";
        UnmarshalOptions small = UnmarshalOptions.defaults()
                .withLimits(DecodeLimits.defaults().withMaxMessageSize("id = \"x\"\nreq = 1\n".length() - 1));
        Bound b = bindUnder(small, ROW, table);
        assertNull(b.error());
        assertEquals(1, b.message().getField(ROW.findFieldByName("req")));
    }
}
