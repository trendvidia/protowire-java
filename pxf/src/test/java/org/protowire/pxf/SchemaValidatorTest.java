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
import org.protowire.pxf.testproto.KeyedBad;
import org.protowire.pxf.testproto.KeyedOk;
import org.protowire.pxf.testproto.OneDefault;
import org.protowire.pxf.testproto.RepeatedDefault;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        assertTrue(ex.getMessage().contains("uses PXF-reserved name \"true\""),
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

    // -- #54: placement checks, on the compiled annotated_invalid.proto ------

    private static Map<String, SchemaValidator.Violation> byElement(List<SchemaValidator.Violation> vs) {
        return vs.stream().collect(Collectors.toMap(SchemaValidator.Violation::element, v -> v, (a, b) -> a));
    }

    @Test
    void placementViolationsInTheInvalidFile() {
        List<SchemaValidator.Violation> vs = SchemaValidator.validateDescriptor(RepeatedDefault.getDescriptor());
        Map<String, SchemaValidator.Violation> by = byElement(vs);
        // Every violation is attributed to the declaring file and sorted by element.
        for (SchemaValidator.Violation v : vs) assertEquals("test/v1/annotated_invalid.proto", v.file(), v.toString());
        List<String> elements = vs.stream().map(SchemaValidator.Violation::element).toList();
        assertEquals(elements.stream().sorted().toList(), elements);

        // Default Placement (§6.1.1)
        assertKind(by, "test.v1.RepeatedDefault.tags", SchemaValidator.Kind.DEFAULT_OPTION, "not valid on repeated fields");
        assertKind(by, "test.v1.RepeatedIntDefault.counts", SchemaValidator.Kind.DEFAULT_OPTION, "not valid on repeated fields");
        assertKind(by, "test.v1.MapDefault.labels", SchemaValidator.Kind.DEFAULT_OPTION, "not valid on map fields");
        assertKind(by, "test.v1.MessageDefault.inner", SchemaValidator.Kind.DEFAULT_OPTION, "not valid on message type test.v1.Plain: no PXF literal denotes it");
        assertEquals("ignored", by.get("test.v1.RepeatedDefault.tags").name());
        assertTrue(by.get("test.v1.RepeatedDefault.tags").toString().contains("invalid (pxf.default) = \"ignored\""), by.get("test.v1.RepeatedDefault.tags").toString());
        // Oneof Members (§6.1.2)
        assertKind(by, "test.v1.RequiredMember.a", SchemaValidator.Kind.REQUIRED_OPTION, "(pxf.required) is not valid on a member of oneof \"choice\"");
        assertEquals("choice", by.get("test.v1.RequiredMember.a").name());
        assertKind(by, "test.v1.TwoDefaults.a", SchemaValidator.Kind.DEFAULT_OPTION, "at most one member of oneof \"choice\" may carry a default; 2 do (a, b)");
        assertKind(by, "test.v1.TwoDefaults.b", SchemaValidator.Kind.DEFAULT_OPTION, "2 do (a, b)");
        assertEquals("aaa", by.get("test.v1.TwoDefaults.a").name());
        assertEquals("bbb", by.get("test.v1.TwoDefaults.b").name());
        // Schema Placement (§3.13.1)
        assertKind(by, "test.v1.KeyedBad.single", SchemaValidator.Kind.KEY_OPTION, "valid only on repeated message-typed fields");
        assertKind(by, "test.v1.KeyedBad.names", SchemaValidator.Kind.KEY_OPTION, "valid only on repeated message-typed fields");
        assertKind(by, "test.v1.KeyedBad.missing", SchemaValidator.Kind.KEY_OPTION, "element message test.v1.Plain has no field \"nope\"");
        assertKind(by, "test.v1.KeyedBad.nonstring", SchemaValidator.Kind.KEY_OPTION, "key field test.v1.Counted.n must be a singular string field");
        assertTrue(by.get("test.v1.KeyedBad.single").toString().contains("invalid (pxf.key) = \"s\""));
        assertEquals(11, vs.size(), vs.toString());
    }

    private static void assertKind(Map<String, SchemaValidator.Violation> by, String element, SchemaValidator.Kind kind, String detail) {
        SchemaValidator.Violation v = by.get(element);
        assertTrue(v != null, element + " not reported; have " + by.keySet());
        assertEquals(kind, v.kind(), v.toString());
        assertTrue(v.detail().contains(detail), v.toString());
    }

    // The conformant file: one default per oneof, a synthetic oneof's
    // default, a well-placed (pxf.key) — and its closure through
    // pxf/annotations.proto and google/protobuf/descriptor.proto.
    @Test
    void conformantFileAndItsClosureAreClean() {
        assertEquals(List.of(), SchemaValidator.validateDescriptor(OneDefault.getDescriptor()));
        assertEquals(List.of(), SchemaValidator.validateDescriptor(KeyedOk.getDescriptor()));
        assertEquals(List.of(), SchemaValidator.validateFile(KeyedBad.getDescriptor().getFile().getDependencies().get(1)),
                "pxf/annotations.proto itself is clean");
    }

    @Test
    void decoderRejectsTheInvalidFileBeforeReadingADocument() {
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.unmarshal("tags = [\"a\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8), RepeatedDefault.newBuilder()));
        assertTrue(e.getMessage().contains("PXF schema bind-time violations:"), e.getMessage());
        assertTrue(e.getMessage().contains("test.v1.KeyedBad.single"), "the whole file's violations are reported: " + e.getMessage());
        // skipValidate bypasses the bind-time check; the document then decodes.
        RepeatedDefault.Builder b = RepeatedDefault.newBuilder();
        UnmarshalOptions.defaults().withSkipValidate(true).unmarshal("tags = [\"a\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8), b);
        assertEquals(1, b.getTagsCount());
    }

    // -- #54: scope is the import closure (§3.15) -----------------------------

    private static FileDescriptorProto.Builder file(String name, String pkg) {
        return FileDescriptorProto.newBuilder().setName(name).setPackage(pkg).setSyntax("proto3");
    }

    private static DescriptorProto messageWithField(String msg, String field) {
        return DescriptorProto.newBuilder().setName(msg)
                .addField(FieldDescriptorProto.newBuilder().setName(field).setNumber(1)
                        .setLabel(Label.LABEL_OPTIONAL).setType(Type.TYPE_STRING))
                .build();
    }

    private static FileDescriptor build(FileDescriptorProto fp, FileDescriptor... deps) {
        try {
            return FileDescriptor.buildFrom(fp, deps);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void reservedNameInImportedFileIsReportedThroughTheImporter() {
        FileDescriptor inner = build(file("inner.proto", "inner.v1").addMessageType(messageWithField("Inner", "null")).build());
        FileDescriptor root = build(file("root.proto", "root.v1").addDependency("inner.proto")
                .addMessageType(messageWithField("Root", "ok")).build(), inner);
        assertEquals(List.of(), SchemaValidator.fileViolations(root), "root itself is clean");
        List<SchemaValidator.Violation> vs = SchemaValidator.validateFile(root);
        assertEquals(1, vs.size(), vs.toString());
        assertEquals("inner.proto", vs.get(0).file(), "attributed to the declaring file, not the bound one");
        assertEquals("inner.v1.Inner.null", vs.get(0).element());
        // And through the decoder: an empty document binds Root, whose closure is not clean.
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.unmarshal(new byte[0], DynamicMessage.newBuilder(root.findMessageTypeByName("Root"))));
        assertTrue(e.getMessage().contains("inner.v1.Inner.null"), e.getMessage());
    }

    @Test
    void transitiveImportDepthAndDiamondReportOnce() {
        FileDescriptor d = build(file("d.proto", "d.v1").addMessageType(messageWithField("D", "true")).build());
        FileDescriptor b = build(file("b.proto", "b.v1").addDependency("d.proto").addMessageType(messageWithField("B", "ok")).build(), d);
        FileDescriptor c = build(file("c.proto", "c.v1").addDependency("d.proto").addMessageType(messageWithField("C", "ok")).build(), d);
        FileDescriptor a = build(file("a.proto", "a.v1").addDependency("b.proto").addDependency("c.proto")
                .addMessageType(messageWithField("A", "ok")).build(), b, c);
        List<SchemaValidator.Violation> vs = SchemaValidator.validateFile(a);
        assertEquals(1, vs.size(), "D reached via B and via C is checked once: " + vs);
        assertEquals("d.v1.D.true", vs.get(0).element());
        assertEquals("d.proto", vs.get(0).file());
    }

    @Test
    void sortedByFileThenElement() {
        FileDescriptor z = build(file("z.proto", "z.v1").addMessageType(messageWithField("M", "null")).build());
        FileDescriptor a = build(file("a.proto", "a.v1").addDependency("z.proto")
                .addMessageType(messageWithField("N", "false")).addMessageType(messageWithField("M", "true")).build(), z);
        List<String> got = SchemaValidator.validateFile(a).stream().map(v -> v.file() + " " + v.element()).toList();
        assertEquals(List.of("a.proto a.v1.M.true", "a.proto a.v1.N.false", "z.proto z.v1.M.null"), got);
    }

    // Two descriptors at the same path with different contents are two
    // cache entries: the memo keys on descriptor identity, not on the path.
    @Test
    void memoIsPerDescriptorNotPerPath() {
        FileDescriptor clean = build(file("same.proto", "same.v1").addMessageType(messageWithField("M", "ok")).build());
        FileDescriptor dirty = build(file("same.proto", "same.v1").addMessageType(messageWithField("M", "null")).build());
        assertEquals(List.of(), SchemaValidator.validateFile(clean));
        assertEquals(1, SchemaValidator.validateFile(dirty).size());
        assertEquals(List.of(), SchemaValidator.validateFile(clean), "the clean file's memo survived the dirty twin");
        assertTrue(SchemaValidator.validateFile(dirty) == SchemaValidator.validateFile(dirty), "memoized: the same immutable list");
    }

    // proto3 `optional` sits in a synthetic oneof, which the oneof rules
    // exclude: (pxf.required) there is fine. Built by hand with the raw
    // option bytes protoc emits for an unresolved extension.
    @Test
    void requiredOnProto3OptionalIsClean() {
        com.google.protobuf.DescriptorProtos.FieldOptions required = com.google.protobuf.DescriptorProtos.FieldOptions.newBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(1314, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build())
                .build();
        FileDescriptorProto fp = file("opt.proto", "opt.v1").addMessageType(DescriptorProto.newBuilder().setName("O")
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_opt"))
                .addField(FieldDescriptorProto.newBuilder().setName("opt").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                        .setType(Type.TYPE_STRING).setOneofIndex(0).setProto3Optional(true).setOptions(required))).build();
        FileDescriptor fd = build(fp);
        assertTrue(fd.getMessageTypes().get(0).getOneofs().get(0).isSynthetic(), "fixture must be a synthetic oneof");
        assertEquals(List.of(), SchemaValidator.validateFile(fd));
        // The same bytes on a real oneof member are the violation.
        FileDescriptorProto bad = file("req.proto", "req.v1").addMessageType(DescriptorProto.newBuilder().setName("R")
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
                .addField(FieldDescriptorProto.newBuilder().setName("a").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                        .setType(Type.TYPE_STRING).setOneofIndex(0).setOptions(required))
                .addField(FieldDescriptorProto.newBuilder().setName("b").setNumber(2).setLabel(Label.LABEL_OPTIONAL)
                        .setType(Type.TYPE_STRING).setOneofIndex(0))).build();
        List<SchemaValidator.Violation> vs = SchemaValidator.validateFile(build(bad));
        assertEquals(1, vs.size(), vs.toString());
        assertEquals(SchemaValidator.Kind.REQUIRED_OPTION, vs.get(0).kind());
        assertEquals("req.v1.R.a", vs.get(0).element());
        assertEquals("choice", vs.get(0).name());
    }

    @Test
    void violationDetailDefaultsToEmpty() {
        SchemaValidator.Violation v = new SchemaValidator.Violation("t.proto", "p.v1.M.null", "null", SchemaValidator.Kind.FIELD);
        assertEquals("", v.detail());
        assertEquals("required field option", SchemaValidator.Kind.REQUIRED_OPTION.toString());
        assertEquals("keyed field option", SchemaValidator.Kind.KEY_OPTION.toString());
        assertEquals("default field option", SchemaValidator.Kind.DEFAULT_OPTION.toString());
    }

    // -- #91: retired option numbers (STABILITY.md promise 3) ------------------

    private static com.google.protobuf.DescriptorProtos.FieldOptions unknownString(int num, String v) {
        return com.google.protobuf.DescriptorProtos.FieldOptions.newBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(num, com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                .addLengthDelimited(com.google.protobuf.ByteString.copyFromUtf8(v)).build()).build())
                .build();
    }

    private static FileDescriptor annotationsFile() {
        return org.protowire.proto.pxf.Annotations.getDescriptor();
    }

    @Test
    void retiredFieldOptionNumberIsDiagnosedAsStale() {
        // (pxf.default) at its pre-v1.12 number 50001, in a file that imports
        // pxf/annotations.proto: the shape a descriptor compiled before the
        // move has.
        FileDescriptorProto fp = file("stale.proto", "stale.v1").addDependency("pxf/annotations.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("M")
                        .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                                .setType(Type.TYPE_INT32).setOptions(unknownString(50001, "7")))).build();
        FileDescriptor fd = build(fp, annotationsFile());
        List<SchemaValidator.Violation> vs = SchemaValidator.validateFile(fd);
        assertEquals(1, vs.size(), vs.toString());
        SchemaValidator.Violation v = vs.get(0);
        assertEquals(SchemaValidator.Kind.RETIRED_NUMBER, v.kind());
        assertEquals("stale.v1.M.n", v.element());
        assertEquals("50001", v.name());
        assertTrue(v.detail().contains("option number 50001 on FieldOptions was (pxf.default) before protowire v1.12.0 and is 1315 now"), v.detail());
        assertTrue(v.detail().contains("recompiled against the current pxf/annotations.proto"), v.detail());
        assertTrue(v.toString().contains("(STABILITY.md promise 3)"), v.toString());
        assertEquals("retired option number", SchemaValidator.Kind.RETIRED_NUMBER.toString());
        // The decoder refuses it by default; skipValidate bypasses as for every bind-time check.
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.unmarshal(new byte[0], DynamicMessage.newBuilder(fd.findMessageTypeByName("M"))));
        assertTrue(e.getMessage().contains("50001"), e.getMessage());
        UnmarshalOptions.defaults().withSkipValidate(true).unmarshal(new byte[0], DynamicMessage.newBuilder(fd.findMessageTypeByName("M")));
    }

    // The same bytes at the registered number are clean, and a 50001 in a
    // file that never imports protowire's annotations is that file's own
    // business.
    @Test
    void registeredNumberAndUnrelatedFilesAreClean() {
        FileDescriptorProto current = file("current.proto", "current.v1").addDependency("pxf/annotations.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("M")
                        .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                                .setType(Type.TYPE_INT32).setOptions(unknownString(1315, "7")))).build();
        assertEquals(List.of(), SchemaValidator.validateFile(build(current, annotationsFile())));
        FileDescriptorProto unrelated = file("other.proto", "other.v1")
                .addMessageType(DescriptorProto.newBuilder().setName("M")
                        .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                                .setType(Type.TYPE_INT32).setOptions(unknownString(50001, "7")))).build();
        assertEquals(List.of(), SchemaValidator.validateFile(build(unrelated)));
    }

    // Each retired number counts only on the Options kind its allocation
    // used: 50200 was (sbe.template_id) on MessageOptions, 50100
    // (sbe.schema_id) on FileOptions; a 50200 on a field is not retired.
    @Test
    void retiredNumbersAreScopedToTheirOptionsKind() {
        com.google.protobuf.DescriptorProtos.MessageOptions mo = com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(50200, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build()).build();
        com.google.protobuf.DescriptorProtos.FileOptions fo = com.google.protobuf.DescriptorProtos.FileOptions.newBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(50100, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()).build();
        FileDescriptorProto fp = file("sbe_stale.proto", "sbestale.v1").addDependency("pxf/annotations.proto").setOptions(fo)
                .addMessageType(DescriptorProto.newBuilder().setName("Order").setOptions(mo)
                        .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(1).setLabel(Label.LABEL_OPTIONAL)
                                .setType(Type.TYPE_INT32).setOptions(unknownString(50200, "x")))).build();
        List<SchemaValidator.Violation> vs = SchemaValidator.validateFile(build(fp, annotationsFile()));
        List<String> got = vs.stream().map(v -> v.element() + "=" + v.name()).toList();
        assertEquals(List.of("sbe_stale.proto=50100", "sbestale.v1.Order=50200"), got, vs.toString());
    }
}
