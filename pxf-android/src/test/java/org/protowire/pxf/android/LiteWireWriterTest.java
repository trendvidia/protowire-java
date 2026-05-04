package org.protowire.pxf.android;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;
import org.protowire.pxf.Position;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;
import org.protowire.pxf.SimplePxfRegistry;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-level wire-format checks for {@link LiteWireWriter}.
 *
 * <p>The expected hex strings are the canonical protobuf wire format for each
 * fixture, computable by hand from {@code descriptor.proto}'s tag-format rules
 * (tag = (fieldNumber << 3) | wireType, varint values written little-endian
 * 7-bit groups). Wire-equivalence with the full-runtime {@code :pxf} module
 * is the larger goal — that's enforced cross-port in {@code cross_envelope_check.sh}
 * once {@code :dump-envelope-android} ships.
 */
final class LiteWireWriterTest {

    /** Minimal hand-coded PxfMeta — tests pass a tailor-made instance to the writer. */
    private record TestMeta(
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Map<Integer, Integer> wireTypes,
            Set<Integer> repeatedFields,
            Set<Integer> packedFields,
            String fullName) implements PxfMeta {

        @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
        @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
        @Override public Set<Integer>          requiredFields()     { return Set.of(); }
        @Override public Map<Integer, String>  defaults()           { return Map.of(); }
        @Override public int                   sbeTemplateId()      { return -1; }
        @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
        @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
        @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
    }

    private static PxfMeta meta(
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Set<Integer> repeated,
            Set<Integer> packed) {
        return new TestMeta(fieldNumbers, fieldKinds, /*wireTypes*/ Map.of(),
                repeated, packed, "test.Sample");
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Test
    void scalarString() {
        // message Sample { string name = 1; }  →  name = "Alice"
        // wire: tag 0x0a (field 1, wireType 2), len 5, "Alice"
        Ast.Document doc = Parser.parse("name = \"Alice\"");
        PxfMeta m = meta(
                Map.of("name", 1),
                Map.of(1, 9 /* STRING */),
                Set.of(),
                Set.of());

        assertEquals("0a05416c696365", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarInt32() {
        // int32 age = 2;  →  age = 30  →  tag 0x10, varint 0x1e
        Ast.Document doc = Parser.parse("age = 30");
        PxfMeta m = meta(
                Map.of("age", 2),
                Map.of(2, 5 /* INT32 */),
                Set.of(),
                Set.of());

        assertEquals("101e", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarBool() {
        // bool flag = 1;  →  flag = true  →  tag 0x08, varint 0x01
        Ast.Document doc = Parser.parse("flag = true");
        PxfMeta m = meta(
                Map.of("flag", 1),
                Map.of(1, 8 /* BOOL */),
                Set.of(),
                Set.of());

        assertEquals("0801", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarBytes() {
        // bytes data = 1;  →  data = b"AQID" (base64 for 0x01 0x02 0x03)
        // → tag 0x0a (field 1, wireType 2), len 3, 01 02 03
        Ast.Document doc = Parser.parse("data = b\"AQID\"");
        PxfMeta m = meta(
                Map.of("data", 1),
                Map.of(1, 12 /* BYTES */),
                Set.of(),
                Set.of());

        assertEquals("0a03010203", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarFloat() {
        // float pi = 1;  →  pi = 1.0  →  tag 0x0d (field 1, wireType 5 / I32), then IEEE-754 bytes 00 00 80 3f
        Ast.Document doc = Parser.parse("pi = 1.0");
        PxfMeta m = meta(
                Map.of("pi", 1),
                Map.of(1, 2 /* FLOAT */),
                Set.of(),
                Set.of());

        assertEquals("0d0000803f", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarDouble() {
        // double pi = 1;  →  pi = 1.0  →  tag 0x09 (field 1, wireType 1 / I64), then IEEE-754 bytes 00 00 00 00 00 00 f0 3f
        Ast.Document doc = Parser.parse("pi = 1.0");
        PxfMeta m = meta(
                Map.of("pi", 1),
                Map.of(1, 1 /* DOUBLE */),
                Set.of(),
                Set.of());

        assertEquals("09000000000000f03f", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void scalarSint32_zigzag() {
        // sint32 n = 1;  →  n = -1  →  tag 0x08, zigzag(-1) = 1 → varint 0x01
        Ast.Document doc = Parser.parse("n = -1");
        PxfMeta m = meta(
                Map.of("n", 1),
                Map.of(1, 17 /* SINT32 */),
                Set.of(),
                Set.of());

        assertEquals("0801", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void twoFields_inDeclarationOrder() {
        // message Person { string name = 1; int32 age = 2; }
        // name = "Alice", age = 30  →  0a 05 "Alice" 10 1e
        Ast.Document doc = Parser.parse("name = \"Alice\"\nage = 30");
        PxfMeta m = meta(
                Map.of("name", 1, "age", 2),
                Map.of(1, 9, 2, 5),
                Set.of(),
                Set.of());

        assertEquals("0a05416c69636510" + "1e", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void repeatedInt32_packed() {
        // repeated int32 lucky = 1;  →  lucky = [1, 2, 300]  →  packed: tag 0x0a (LEN), len 4, 01 02 ac02
        Ast.Document doc = Parser.parse("lucky = [1, 2, 300]");
        PxfMeta m = meta(
                Map.of("lucky", 1),
                Map.of(1, 5 /* INT32 */),
                Set.of(1),
                Set.of(1));

        assertEquals("0a0401" + "02" + "ac02", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void repeatedString_neverPacked() {
        // repeated string aliases = 1;  →  aliases = ["a", "bb"]
        // Strings can't pack; emit two length-delimited records.
        Ast.Document doc = Parser.parse("aliases = [\"a\", \"bb\"]");
        PxfMeta m = meta(
                Map.of("aliases", 1),
                Map.of(1, 9 /* STRING */),
                Set.of(1),
                Set.of() /* PACKED_FIELDS deliberately empty for strings */);

        assertEquals("0a0161" + "0a026262", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void unknownFieldName_throws() {
        Ast.Document doc = Parser.parse("nope = 1");
        PxfMeta m = meta(Map.of(), Map.of(), Set.of(), Set.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals("unknown field name: nope", e.getMessage());
    }

    @Test
    void emptyDocument_emitsNoBytes() {
        Ast.Document doc = Parser.parse("");
        PxfMeta m = meta(Map.of(), Map.of(), Set.of(), Set.of());

        assertArrayEquals(new byte[0], LiteWireWriter.encode(doc, m));
    }

    // --- nested message + enum --------------------------------------------

    /** Anonymous PxfMeta with messageTypes/enumTypes overridable — used by nested tests. */
    private static PxfMeta richMeta(
            String fullName,
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Map<Integer, String> messageTypes,
            Map<Integer, String> enumTypes) {
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return fieldNumbers; }
            @Override public Map<Integer, Integer> fieldKinds()         { return fieldKinds; }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return messageTypes; }
            @Override public Map<Integer, String>  enumTypes()          { return enumTypes; }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
    }

    @Test
    void nestedMessage_blockSyntax() {
        // message Outer { Inner inner = 1; }
        // message Inner { string s = 1; }
        // PXF: inner { s = "hi" }
        // Inner wire bytes: 0a 02 "hi" → 0a 02 68 69     (total 4 bytes)
        // Outer wire bytes: tag 0x0a (field 1, LEN), len 4, then nested → 0a 04 0a 02 68 69
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta innerMeta = richMeta("test.Inner",
                Map.of("s", 1),
                Map.of(1, 9 /* STRING */),
                Map.of(),
                Map.of());

        PxfMeta outerMeta = richMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11 /* MESSAGE */),
                Map.of(1, "test.Inner"),
                Map.of());

        PxfRegistry registry = new SimplePxfRegistry()
                .register(innerMeta);

        assertEquals("0a040a026869", hex(LiteWireWriter.encode(doc, outerMeta, registry)));
    }

    @Test
    void enum_byIdentifier_resolvedThroughRegistry() {
        // message Sample { Status status = 1; }
        // enum Status { STATUS_UNKNOWN = 0; STATUS_ACTIVE = 1; }
        // PXF: status = STATUS_ACTIVE  →  tag 0x08 (field 1, varint), value 0x01
        Ast.Document doc = Parser.parse("status = STATUS_ACTIVE");

        PxfEnum statusEnum = new PxfEnum() {
            @Override public String fullName() { return "test.Status"; }
            @Override public Map<String, Integer> values() {
                return Map.of("STATUS_UNKNOWN", 0, "STATUS_ACTIVE", 1);
            }
            @Override public Map<Integer, String> names() {
                return Map.of(0, "STATUS_UNKNOWN", 1, "STATUS_ACTIVE");
            }
        };

        PxfMeta sampleMeta = richMeta("test.Sample",
                Map.of("status", 1),
                Map.of(1, 14 /* ENUM */),
                Map.of(),
                Map.of(1, "test.Status"));

        PxfRegistry registry = new SimplePxfRegistry().register(statusEnum);

        assertEquals("0801", hex(LiteWireWriter.encode(doc, sampleMeta, registry)));
    }

    @Test
    void enum_byIntegerLiteral_acceptedDirectly() {
        // PXF allows bare integers for enum-typed fields (forward-compat / unknown values).
        Ast.Document doc = Parser.parse("status = 1");

        PxfMeta sampleMeta = richMeta("test.Sample",
                Map.of("status", 1),
                Map.of(1, 14 /* ENUM */),
                Map.of(),
                Map.of(1, "test.Status"));

        // No PxfEnum needs to be registered — integer path bypasses the lookup.
        PxfRegistry registry = new SimplePxfRegistry();

        assertEquals("0801", hex(LiteWireWriter.encode(doc, sampleMeta, registry)));
    }

    @Test
    void nestedMessage_missingRegistryEntry_throws() {
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta outerMeta = richMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11 /* MESSAGE */),
                Map.of(1, "test.Inner"),
                Map.of());

        PxfRegistry registry = new SimplePxfRegistry();  // empty

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, outerMeta, registry));
        assertEquals(
            "PxfRegistry has no entry for nested message 'test.Inner' (referenced from field 1 of test.Outer)",
            e.getMessage());
    }

    @Test
    void enum_unknownValueName_throws() {
        Ast.Document doc = Parser.parse("status = STATUS_DELETED");

        PxfEnum statusEnum = new PxfEnum() {
            @Override public String fullName() { return "test.Status"; }
            @Override public Map<String, Integer> values() { return Map.of("STATUS_UNKNOWN", 0); }
            @Override public Map<Integer, String> names()  { return Map.of(0, "STATUS_UNKNOWN"); }
        };

        PxfMeta sampleMeta = richMeta("test.Sample",
                Map.of("status", 1),
                Map.of(1, 14 /* ENUM */),
                Map.of(),
                Map.of(1, "test.Status"));

        PxfRegistry registry = new SimplePxfRegistry().register(statusEnum);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, sampleMeta, registry));
        assertEquals("unknown enum value 'STATUS_DELETED' for test.Status", e.getMessage());
    }

    @Test
    void twoArgEncode_throwsIfNestedFieldHit() {
        // Verifies the sentinel registry: 2-arg encode against a schema with a
        // nested-message field fails fast and points at the 3-arg overload.
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta outerMeta = richMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11 /* MESSAGE */),
                Map.of(1, "test.Inner"),
                Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LiteWireWriter.encode(doc, outerMeta));
        assertEquals(true, e.getMessage().contains("encode(doc, meta, registry)"));
    }

    // --- embedded nested/enum lookup --------------------------------------

    /** PxfMeta that overrides nestedMetas() / enumMetas() — the embedded fast path. */
    private static PxfMeta embeddingMeta(
            String fullName,
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Map<Integer, String> messageTypes,
            Map<Integer, String> enumTypes,
            Map<Integer, PxfMeta> embeddedNested,
            Map<Integer, PxfEnum> embeddedEnums) {
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return fieldNumbers; }
            @Override public Map<Integer, Integer> fieldKinds()         { return fieldKinds; }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return messageTypes; }
            @Override public Map<Integer, String>  enumTypes()          { return enumTypes; }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfMeta> nestedMetas()        { return embeddedNested; }
            @Override public Map<Integer, PxfEnum> enumMetas()          { return embeddedEnums; }
        };
    }

    @Test
    void nestedMessage_viaEmbedded_no2argRegistryNeeded() {
        // Embedded fast path: meta carries a direct nested PxfMeta reference,
        // so 2-arg encode (sentinel registry) succeeds even though the schema
        // has a message-typed field.
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta innerMeta = richMeta("test.Inner",
                Map.of("s", 1),
                Map.of(1, 9 /* STRING */),
                Map.of(),
                Map.of());

        PxfMeta outerMeta = embeddingMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11 /* MESSAGE */),
                Map.of(1, "test.Inner"),
                Map.of(),
                Map.of(1, innerMeta),
                Map.of());

        // No registry — 2-arg encode works because nestedMetas() carries the reference.
        assertEquals("0a040a026869", hex(LiteWireWriter.encode(doc, outerMeta)));
    }

    @Test
    void enum_viaEmbedded_no2argRegistryNeeded() {
        Ast.Document doc = Parser.parse("status = STATUS_ACTIVE");

        PxfEnum statusEnum = new PxfEnum() {
            @Override public String fullName() { return "test.Status"; }
            @Override public Map<String, Integer> values() {
                return Map.of("STATUS_UNKNOWN", 0, "STATUS_ACTIVE", 1);
            }
            @Override public Map<Integer, String> names() {
                return Map.of(0, "STATUS_UNKNOWN", 1, "STATUS_ACTIVE");
            }
        };

        PxfMeta sampleMeta = embeddingMeta("test.Sample",
                Map.of("status", 1),
                Map.of(1, 14 /* ENUM */),
                Map.of(),
                Map.of(1, "test.Status"),
                Map.of(),
                Map.of(1, statusEnum));

        assertEquals("0801", hex(LiteWireWriter.encode(doc, sampleMeta)));
    }

    @Test
    void embedded_takesPrecedenceOverRegistry() {
        // When embedded carries a reference for the field, registry is irrelevant
        // — even if the registry has a (different) entry for the same FQN.
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta correctInnerMeta = richMeta("test.Inner",
                Map.of("s", 1),
                Map.of(1, 9),
                Map.of(),
                Map.of());

        // A bogus meta sharing the same fullName but a different field-number
        // mapping — registry would emit different bytes if it won.
        PxfMeta bogusInnerMeta = richMeta("test.Inner",
                Map.of("s", 99),
                Map.of(99, 9),
                Map.of(),
                Map.of());

        PxfMeta outerMeta = embeddingMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11),
                Map.of(1, "test.Inner"),
                Map.of(),
                Map.of(1, correctInnerMeta),
                Map.of());

        PxfRegistry registry = new SimplePxfRegistry().register(bogusInnerMeta);

        // Field-1 encoding (0a 02 "hi" inside the LEN payload), confirming embedded won.
        assertEquals("0a040a026869", hex(LiteWireWriter.encode(doc, outerMeta, registry)));
    }

    @Test
    void embeddedMissing_fallsThroughToRegistry() {
        // Embedded map empty — registry covers the gap.
        Ast.Document doc = Parser.parse("inner { s = \"hi\" }");

        PxfMeta innerMeta = richMeta("test.Inner",
                Map.of("s", 1),
                Map.of(1, 9),
                Map.of(),
                Map.of());

        PxfMeta outerMeta = embeddingMeta("test.Outer",
                Map.of("inner", 1),
                Map.of(1, 11),
                Map.of(1, "test.Inner"),
                Map.of(),
                Map.of(),
                Map.of());

        PxfRegistry registry = new SimplePxfRegistry().register(innerMeta);

        assertEquals("0a040a026869", hex(LiteWireWriter.encode(doc, outerMeta, registry)));
    }

    // --- required / default / oneof semantics ------------------------------

    /** PxfMeta with overridable required / defaults / oneofOf for the semantics tests. */
    private static PxfMeta semanticMeta(
            String fullName,
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Set<Integer> requiredFields,
            Map<Integer, String> defaults,
            Map<Integer, String> oneofOf) {
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return fieldNumbers; }
            @Override public Map<Integer, Integer> fieldKinds()         { return fieldKinds; }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return requiredFields; }
            @Override public Map<Integer, String>  defaults()           { return defaults; }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return oneofOf; }
        };
    }

    @Test
    void requiredField_set_succeeds() {
        Ast.Document doc = Parser.parse("name = \"Alice\"");
        PxfMeta m = semanticMeta("test.Sample",
                Map.of("name", 1),
                Map.of(1, 9),
                Set.of(1),
                Map.of(),
                Map.of());

        // Encodes cleanly because the required field is set.
        assertEquals("0a05416c696365", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void requiredField_absent_throws() {
        Ast.Document doc = Parser.parse("");  // nothing set
        PxfMeta m = semanticMeta("test.Sample",
                Map.of("name", 1, "age", 2),
                Map.of(1, 9, 2, 5),
                Set.of(1),  // name is required
                Map.of(),
                Map.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals("required field(s) missing in test.Sample: name", e.getMessage());
    }

    @Test
    void requiredField_satisfiedByDefault() {
        // A required field with a default is unusual but valid: the default
        // satisfies the required check.
        Ast.Document doc = Parser.parse("");
        PxfMeta m = semanticMeta("test.Sample",
                Map.of("status", 1),
                Map.of(1, 5 /* INT32 */),
                Set.of(1),
                Map.of(1, "42"),
                Map.of());

        // Default fires → field is set → required check passes.
        // Wire bytes: tag 0x08 (field 1, varint), value 42 → 08 2a
        assertEquals("082a", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void default_appliedWhenAbsent() {
        Ast.Document doc = Parser.parse("");  // age not set
        PxfMeta m = semanticMeta("test.Sample",
                Map.of("age", 1),
                Map.of(1, 5 /* INT32 */),
                Set.of(),
                Map.of(1, "30"),
                Map.of());

        // Default 30 → tag 0x08, varint 0x1e
        assertEquals("081e", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void default_skippedWhenSet() {
        Ast.Document doc = Parser.parse("age = 100");  // user-set
        PxfMeta m = semanticMeta("test.Sample",
                Map.of("age", 1),
                Map.of(1, 5 /* INT32 */),
                Set.of(),
                Map.of(1, "30"),  // default would be 30
                Map.of());

        // User-set 100 wins; default 30 NOT applied → tag 0x08, varint 0x64
        assertEquals("0864", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void default_skippedWhenOneofSiblingSet() {
        // figure { radius = 1.0 }  →  radius set; sides also has a default
        // but since they share the oneof "figure", the default for sides
        // must NOT apply.
        Ast.Document doc = Parser.parse("radius = 1.0");
        PxfMeta m = semanticMeta("test.Shape",
                Map.of("radius", 2, "sides", 3),
                Map.of(2, 1 /* DOUBLE */, 3, 5 /* INT32 */),
                Set.of(),
                Map.of(3, "4"),   // sides defaults to 4 — should NOT fire here
                Map.of(2, "figure", 3, "figure"));

        // Only radius=1.0 in output: tag 0x11 (field 2, fixed64), 1.0 IEEE-754
        assertEquals("11000000000000f03f", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void oneofCollision_throws() {
        Ast.Document doc = Parser.parse("radius = 1.0\nsides = 4");
        PxfMeta m = semanticMeta("test.Shape",
                Map.of("radius", 2, "sides", 3),
                Map.of(2, 1, 3, 5),
                Set.of(),
                Map.of(),
                Map.of(2, "figure", 3, "figure"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals(
            "oneof 'figure' in test.Shape already has 'radius' set; cannot also set 'sides'",
            e.getMessage());
    }

    // --- maps -------------------------------------------------------------

    /** PxfMeta for a synthetic map-entry submessage (key=1, value=2). */
    private static PxfMeta mapEntryMeta(
            String fullName,
            int keyKind,
            int valueKind,
            Map<Integer, String> messageTypes,
            Map<Integer, String> enumTypes,
            Map<Integer, PxfMeta> nestedMetas,
            Map<Integer, PxfEnum> enumMetas) {
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("key", 1, "value", 2); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, keyKind, 2, valueKind); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return messageTypes; }
            @Override public Map<Integer, String>  enumTypes()          { return enumTypes; }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfMeta> nestedMetas()        { return nestedMetas; }
            @Override public Map<Integer, PxfEnum> enumMetas()          { return enumMetas; }
        };
    }

    /** PxfMeta with overridable mapFields() + nestedMetas() for the parent of a map field. */
    private static PxfMeta mapHostMeta(
            String fullName,
            Map<String, Integer> fieldNumbers,
            Map<Integer, Integer> fieldKinds,
            Set<Integer> repeated,
            Map<Integer, String> messageTypes,
            Map<Integer, PxfMeta> nestedMetas,
            Set<Integer> mapFields) {
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return fieldNumbers; }
            @Override public Map<Integer, Integer> fieldKinds()         { return fieldKinds; }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return repeated; }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return messageTypes; }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfMeta> nestedMetas()        { return nestedMetas; }
            @Override public Set<Integer>          mapFields()          { return mapFields; }
        };
    }

    @Test
    void map_stringToString_singleEntry() {
        // map<string, string> labels = 1;  →  labels { "env": "prod" }
        // Entry submessage: key (string,1) = "env", value (string,2) = "prod"
        // Entry bytes: 0a 03 "env" 12 04 "prod"  →  0a 03 656e76 12 04 70726f64  (11 bytes)
        // Outer record: tag(1, LEN) = 0x0a, len = 0x0b, payload
        // Total: 0a 0b 0a 03 656e76 12 04 70726f64
        Ast.Document doc = Parser.parse("labels { \"env\": \"prod\" }");

        PxfMeta entry = mapEntryMeta("test.Sample.LabelsEntry",
                9 /* STRING */, 9 /* STRING */,
                Map.of(), Map.of(), Map.of(), Map.of());
        PxfMeta host = mapHostMeta("test.Sample",
                Map.of("labels", 1),
                Map.of(1, 11 /* MESSAGE */),
                Set.of(1),  // map fields are wire-repeated
                Map.of(1, "test.Sample.LabelsEntry"),
                Map.of(1, entry),
                Set.of(1));

        assertEquals("0a0b0a03656e76120470726f64", hex(LiteWireWriter.encode(doc, host)));
    }

    @Test
    void map_intToString() {
        // map<int32, string> codes = 1;  →  codes { 404: "not found" }
        // Entry bytes: 08 94 03 (key field 1, varint 404 = 0x94 0x03) 12 09 "not found"
        //   = 08 9403 12 09 6e6f7420666f756e64    (14 bytes)
        // Outer: 0a 0e <14 bytes>
        Ast.Document doc = Parser.parse("codes { 404: \"not found\" }");

        PxfMeta entry = mapEntryMeta("test.Sample.CodesEntry",
                5 /* INT32 */, 9 /* STRING */,
                Map.of(), Map.of(), Map.of(), Map.of());
        PxfMeta host = mapHostMeta("test.Sample",
                Map.of("codes", 1),
                Map.of(1, 11),
                Set.of(1),
                Map.of(1, "test.Sample.CodesEntry"),
                Map.of(1, entry),
                Set.of(1));

        assertEquals("0a0e08940312096e6f7420666f756e64", hex(LiteWireWriter.encode(doc, host)));
    }

    @Test
    void map_emptyBlock_emitsNoBytes() {
        Ast.Document doc = Parser.parse("labels {}");

        PxfMeta entry = mapEntryMeta("test.Sample.LabelsEntry",
                9, 9, Map.of(), Map.of(), Map.of(), Map.of());
        PxfMeta host = mapHostMeta("test.Sample",
                Map.of("labels", 1),
                Map.of(1, 11),
                Set.of(1),
                Map.of(1, "test.Sample.LabelsEntry"),
                Map.of(1, entry),
                Set.of(1));

        // No entries → no records emitted.
        assertEquals("", hex(LiteWireWriter.encode(doc, host)));
    }

    @Test
    void map_twoEntries_emitsTwoRecords() {
        Ast.Document doc = Parser.parse("labels { \"env\": \"prod\"\n\"team\": \"platform\" }");

        PxfMeta entry = mapEntryMeta("test.Sample.LabelsEntry",
                9, 9, Map.of(), Map.of(), Map.of(), Map.of());
        PxfMeta host = mapHostMeta("test.Sample",
                Map.of("labels", 1),
                Map.of(1, 11),
                Set.of(1),
                Map.of(1, "test.Sample.LabelsEntry"),
                Map.of(1, entry),
                Set.of(1));

        // Entry 1: 0a 03 "env" 12 04 "prod" (11 bytes) → outer: 0a 0b ...
        // Entry 2: 0a 04 "team" 12 08 "platform" (16 bytes) → outer: 0a 10 ...
        String entry1 = "0a0b0a03656e76120470726f64";
        String entry2 = "0a100a047465616d12087" + "06c6174666f726d";
        assertEquals(entry1 + entry2, hex(LiteWireWriter.encode(doc, host)));
    }

    @Test
    void mapEntry_outsideMapBlock_throws() {
        // The parser would normally only emit MapEntry inside a map Block.
        // Manually construct an AST with a top-level MapEntry to verify the
        // encoder rejects it with a clear message.
        Ast.Document doc = new Ast.Document("",
            List.of(new Ast.MapEntry(
                Position.UNKNOWN, "k",
                new Ast.StringVal(Position.UNKNOWN, "v"),
                List.of(), "")),
            List.of());

        PxfMeta m = mapHostMeta("test.Sample",
                Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Set.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals(true, e.getMessage().contains("outside a map field block"));
    }

    // --- well-known types: Timestamp + Duration ---------------------------

    /**
     * PxfMeta with a single MESSAGE-typed field whose target is one of the
     * recognized WKTs. Populates both {@code messageTypes()} (for parity with
     * codegen-emitted shape) and {@code wellKnownKinds()} (the load-bearing
     * field — the encoder dispatches off this since the FQN-based path was
     * retired alongside the decoder migration).
     */
    private static PxfMeta wellKnownHostMeta(
            String fullName, String fieldName, int fieldNumber, String javaTargetFqn) {
        int wktKind = wktKindFromJavaFqn(javaTargetFqn);
        return new PxfMeta() {
            @Override public String                fullName()           { return fullName; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of(fieldName, fieldNumber); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(fieldNumber, 11 /* MESSAGE */); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(fieldNumber, javaTargetFqn); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, Integer> wellKnownKinds()     { return Map.of(fieldNumber, wktKind); }
        };
    }

    /** Look up the WKT_* kind for one of the recognized WKT Java FQNs (test-only). */
    private static int wktKindFromJavaFqn(String fqn) {
        return switch (fqn) {
            case "com.google.protobuf.Timestamp"     -> PxfMeta.WKT_TIMESTAMP;
            case "com.google.protobuf.Duration"      -> PxfMeta.WKT_DURATION;
            case "com.google.protobuf.StringValue"   -> PxfMeta.WKT_STRING_VALUE;
            case "com.google.protobuf.BytesValue"    -> PxfMeta.WKT_BYTES_VALUE;
            case "com.google.protobuf.BoolValue"     -> PxfMeta.WKT_BOOL_VALUE;
            case "com.google.protobuf.Int32Value"    -> PxfMeta.WKT_INT32_VALUE;
            case "com.google.protobuf.Int64Value"    -> PxfMeta.WKT_INT64_VALUE;
            case "com.google.protobuf.UInt32Value"   -> PxfMeta.WKT_UINT32_VALUE;
            case "com.google.protobuf.UInt64Value"   -> PxfMeta.WKT_UINT64_VALUE;
            case "com.google.protobuf.FloatValue"    -> PxfMeta.WKT_FLOAT_VALUE;
            case "com.google.protobuf.DoubleValue"   -> PxfMeta.WKT_DOUBLE_VALUE;
            case "org.protowire.proto.pxf.BigInt"    -> PxfMeta.WKT_BIG_INT;
            case "org.protowire.proto.pxf.Decimal"   -> PxfMeta.WKT_DECIMAL;
            case "org.protowire.proto.pxf.BigFloat"  -> PxfMeta.WKT_BIG_FLOAT;
            default -> PxfMeta.WKT_NONE;
        };
    }

    @Test
    void timestamp_atEpoch_emitsZeroPayload() {
        // created = 1970-01-01T00:00:00Z  →  seconds=0, nanos=0  →  empty submessage
        // Wire: tag(1, LEN) = 0x0a, len = 0x00
        Ast.Document doc = Parser.parse("created = 1970-01-01T00:00:00Z");
        PxfMeta m = wellKnownHostMeta("test.Sample", "created", 1, "com.google.protobuf.Timestamp");

        assertEquals("0a00", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void timestamp_secondsOnly() {
        // created = 1970-01-01T00:00:01Z  →  seconds=1, nanos=0
        // Inner: tag(1, varint) + 0x01  =  08 01  (2 bytes)
        // Outer: 0a 02 08 01
        Ast.Document doc = Parser.parse("created = 1970-01-01T00:00:01Z");
        PxfMeta m = wellKnownHostMeta("test.Sample", "created", 1, "com.google.protobuf.Timestamp");

        assertEquals("0a020801", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void timestamp_secondsAndNanos() {
        // created = 1970-01-01T00:00:01.000000500Z  →  seconds=1, nanos=500
        // Inner: 08 01 10 f4 03  (5 bytes;  500 = 0xf4 0x03 in varint)
        // Outer: 0a 05 08 01 10 f4 03
        Ast.Document doc = Parser.parse("created = 1970-01-01T00:00:01.000000500Z");
        PxfMeta m = wellKnownHostMeta("test.Sample", "created", 1, "com.google.protobuf.Timestamp");

        assertEquals("0a050801" + "10f403", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void duration_hoursAndMinutes() {
        // ttl = 1h30m  →  seconds = 5400, nanos = 0
        // 5400 in varint: low 7 bits = 5400 & 0x7f = 0x18, with continuation = 0x98;
        // next 7 bits = 5400 >> 7 = 42 = 0x2a, no continuation. So 5400 = 98 2a.
        // Inner: tag(1, varint) + 98 2a  =  08 98 2a  (3 bytes)
        // Outer: 0a 03 08 98 2a
        Ast.Document doc = Parser.parse("ttl = 1h30m");
        PxfMeta m = wellKnownHostMeta("test.Sample", "ttl", 1, "com.google.protobuf.Duration");

        assertEquals("0a0308982a", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void duration_withNanos() {
        // ttl = 100ms  →  seconds = 0, nanos = 100_000_000
        // Inner: tag(2, varint) + varint(100_000_000)  =  10 80 c2 d7 2f  (5 bytes)
        //   100_000_000 in varint:
        //     bin: 0000 0101 1111 0101 1110 0001 0000 0000  =  100_000_000
        //     7-bit groups (LSB-first): 0000000, 0000010, 1111011, 1100001, 0000000
        //     ... but easier to compute via known: 100M = 0x80 0xc2 0xd7 0x2f in varint.
        // Outer: 0a 05 10 80 c2 d7 2f
        Ast.Document doc = Parser.parse("ttl = 100ms");
        PxfMeta m = wellKnownHostMeta("test.Sample", "ttl", 1, "com.google.protobuf.Duration");

        assertEquals("0a051080c2d72f", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void timestamp_onWrongFqnTarget_throws() {
        // Field is MESSAGE-typed but its wellKnownKinds() entry isn't TIMESTAMP
        // (here: WKT_NONE because the meta's javaTargetFqn doesn't map to a WKT).
        // Encoding a TimestampVal at it should fail loudly so the user notices,
        // instead of silently producing Timestamp-shaped bytes into a misshapen
        // target.
        Ast.Document doc = Parser.parse("created = 2024-01-15T10:30:00Z");
        PxfMeta m = wellKnownHostMeta("test.Sample", "created", 1, "com.example.SomeOtherType");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals(true, e.getMessage().contains("requires WKT kind " + PxfMeta.WKT_TIMESTAMP));
        assertEquals(true, e.getMessage().contains("<unset>"));
    }

    @Test
    void duration_onWrongFqnTarget_throws() {
        Ast.Document doc = Parser.parse("ttl = 1h");
        PxfMeta m = wellKnownHostMeta("test.Sample", "ttl", 1, "com.example.SomeOtherType");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals(true, e.getMessage().contains("requires WKT kind " + PxfMeta.WKT_DURATION));
    }

    // --- well-known *Value wrappers + null sentinel -----------------------

    @Test
    void wrapper_stringValue_emitsValueField() {
        // name = "Alice" for StringValue
        // Inner: tag(1, LEN) + len 5 + "Alice" = 0a 05 41 6c 69 63 65 (7 bytes)
        // Outer: tag(1, LEN) + len 7 + inner
        Ast.Document doc = Parser.parse("name = \"Alice\"");
        PxfMeta m = wellKnownHostMeta("test.Sample", "name", 1, "com.google.protobuf.StringValue");

        assertEquals("0a070a05416c696365", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_int32Value_zero_emitsEmptyPayload() {
        // count = 0 for Int32Value → proto3 omits default-zero scalars
        // Inner: <empty>; Outer: 0a 00
        Ast.Document doc = Parser.parse("count = 0");
        PxfMeta m = wellKnownHostMeta("test.Sample", "count", 1, "com.google.protobuf.Int32Value");

        assertEquals("0a00", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_int32Value_nonZero() {
        // count = 42 for Int32Value: inner tag(1, varint) + 42 = 08 2a
        // Outer: 0a 02 08 2a
        Ast.Document doc = Parser.parse("count = 42");
        PxfMeta m = wellKnownHostMeta("test.Sample", "count", 1, "com.google.protobuf.Int32Value");

        assertEquals("0a02082a", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_boolValue_true() {
        // flag = true for BoolValue: inner tag(1, varint) + 1 = 08 01
        // Outer: 0a 02 08 01
        Ast.Document doc = Parser.parse("flag = true");
        PxfMeta m = wellKnownHostMeta("test.Sample", "flag", 1, "com.google.protobuf.BoolValue");

        assertEquals("0a020801", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_doubleValue() {
        // pi = 1.0 for DoubleValue
        // Inner: tag(1, I64) 0x09 + IEEE-754 1.0 = 09 00 00 00 00 00 00 f0 3f (9 bytes)
        // Outer: 0a 09 09 00 00 00 00 00 00 f0 3f
        Ast.Document doc = Parser.parse("pi = 1.0");
        PxfMeta m = wellKnownHostMeta("test.Sample", "pi", 1, "com.google.protobuf.DoubleValue");

        assertEquals("0a0909000000000000f03f", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_bytesValue() {
        // data = b"AQID" (base64 → 01 02 03) for BytesValue
        // Inner: tag(1, LEN) + len 3 + 01 02 03 = 0a 03 01 02 03 (5 bytes)
        // Outer: 0a 05 0a 03 01 02 03
        Ast.Document doc = Parser.parse("data = b\"AQID\"");
        PxfMeta m = wellKnownHostMeta("test.Sample", "data", 1, "com.google.protobuf.BytesValue");

        assertEquals("0a050a03010203", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void wrapper_emptyString_emitsEmptyPayload() {
        // name = "" for StringValue → inner omitted (empty string is the default)
        // Outer: 0a 00
        Ast.Document doc = Parser.parse("name = \"\"");
        PxfMeta m = wellKnownHostMeta("test.Sample", "name", 1, "com.google.protobuf.StringValue");

        assertEquals("0a00", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void null_emitsNoBytes_onWrapperField() {
        // name = null → PXF null sentinel: present for required validation,
        // but no wire bytes emitted.
        Ast.Document doc = Parser.parse("name = null");
        PxfMeta m = wellKnownHostMeta("test.Sample", "name", 1, "com.google.protobuf.StringValue");

        assertEquals("", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void null_satisfiesRequired() {
        // required field set to null → required validation passes, no bytes.
        Ast.Document doc = Parser.parse("name = null");
        PxfMeta m = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("name", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 11 /* MESSAGE */); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(1, "com.google.protobuf.StringValue"); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(1); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };

        // Should NOT throw — null counts as present per the (pxf.required) Javadoc.
        assertEquals("", hex(LiteWireWriter.encode(doc, m)));
    }

    // --- pxf.BigInt / pxf.Decimal / pxf.BigFloat --------------------------

    @Test
    void bigInt_positiveSmall() {
        // n = 42 for pxf.BigInt
        // abs = sign-trimmed BigInteger(42).toByteArray() = [0x2a]
        // negative omitted (signum > 0)
        // Inner: tag(1, LEN) + len 1 + 0x2a = 0a 01 2a (3 bytes)
        // Outer: 0a 03 0a 01 2a
        Ast.Document doc = Parser.parse("n = 42");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        assertEquals("0a030a012a", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigInt_negative() {
        // n = -42 → abs = [0x2a], negative = true
        // Inner: 0a 01 2a (abs) + 10 01 (negative=true) = 5 bytes
        // Outer: 0a 05 0a 01 2a 10 01
        Ast.Document doc = Parser.parse("n = -42");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        assertEquals("0a050a012a1001", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigInt_zero_emitsEmptyPayload() {
        // n = 0 → abs empty + negative omitted → empty payload
        // Outer: 0a 00
        Ast.Document doc = Parser.parse("n = 0");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        assertEquals("0a00", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigInt_largeCrossingSignByteBoundary() {
        // n = 256 → BigInteger(256).toByteArray() = [0x01, 0x00] (high bit clear → no sign byte to trim)
        // abs = [0x01, 0x00]
        // Inner: 0a 02 01 00 (4 bytes)
        // Outer: 0a 04 0a 02 01 00
        Ast.Document doc = Parser.parse("n = 256");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        assertEquals("0a040a020100", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigInt_signByteIsTrimmed() {
        // n = 129 → BigInteger(129).toByteArray() = [0x00, 0x81] (sign-bit padding)
        // After trimSign: [0x81]
        // Inner: 0a 01 81 (3 bytes)
        // Outer: 0a 03 0a 01 81
        Ast.Document doc = Parser.parse("n = 129");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        assertEquals("0a030a0181", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void decimal_withScale() {
        // d = 1.50 → BigDecimal("1.50") has unscaledValue=150, scale=2.
        // 150 = 0x96; BigInteger(150).toByteArray() = [0x00, 0x96], trimmed = [0x96].
        // Inner: 0a 01 96 (unscaled, 3 bytes) + 10 02 (scale, 2 bytes) = 5 bytes
        // Outer: 0a 05 0a 01 96 10 02
        Ast.Document doc = Parser.parse("d = 1.50");
        PxfMeta m = wellKnownHostMeta("test.Sample", "d", 1, "org.protowire.proto.pxf.Decimal");

        assertEquals("0a050a01961002", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void decimal_negative() {
        // d = -1.5 → unscaledValue=15, scale=1, negative=true
        // 15 = 0x0f; toByteArray = [0x0f], no trim needed.
        // Inner: 0a 01 0f + 10 01 + 18 01 = 7 bytes
        // Outer: 0a 07 0a 01 0f 10 01 18 01
        Ast.Document doc = Parser.parse("d = -1.5");
        PxfMeta m = wellKnownHostMeta("test.Sample", "d", 1, "org.protowire.proto.pxf.Decimal");

        assertEquals("0a070a010f10011801", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void decimal_zero_emitsEmptyPayload() {
        // d = 0 → unscaled empty + scale 0 omitted + negative omitted
        // Outer: 0a 00
        Ast.Document doc = Parser.parse("d = 0");
        PxfMeta m = wellKnownHostMeta("test.Sample", "d", 1, "org.protowire.proto.pxf.Decimal");

        assertEquals("0a00", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigFloat_integer() {
        // bf = 100 → BigDecimal("100") has unscaledValue=100, scale=0.
        // mantissa = trimSign([0x64]) = [0x64]; exponent = -scale = 0 (omitted);
        // prec = bitLength(100) = 7; negative omitted.
        // Inner: 0a 01 64 + 18 07 = 5 bytes
        // Outer: 0a 05 0a 01 64 18 07
        Ast.Document doc = Parser.parse("bf = 100");
        PxfMeta m = wellKnownHostMeta("test.Sample", "bf", 1, "org.protowire.proto.pxf.BigFloat");

        assertEquals("0a050a01641807", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigFloat_fractional_negativeExponentVarint() {
        // bf = 1.5 → BigDecimal("1.5") has unscaledValue=15, scale=1.
        // mantissa = [0x0f]; exponent = -scale = -1; prec = bitLength(15) = 4.
        // -1 as int32 protobuf-varint takes 10 bytes (sign-extended to int64):
        //   ff ff ff ff ff ff ff ff ff 01
        // Inner: 0a 01 0f (3) + 10 + 10-byte exponent (11) + 18 04 (2) = 16 bytes
        // Outer: 0a 10 0a 01 0f 10 ff ff ff ff ff ff ff ff ff 01 18 04
        Ast.Document doc = Parser.parse("bf = 1.5");
        PxfMeta m = wellKnownHostMeta("test.Sample", "bf", 1, "org.protowire.proto.pxf.BigFloat");

        assertEquals("0a100a010f10ffffffffffffffffff011804",
                hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigFloat_zero_emitsPrecOnly() {
        // bf = 0 → mantissa empty, exponent omitted, prec defaults to 53,
        // negative omitted. The always-emitted prec field means even a
        // zero BigFloat has wire bytes — matches the JVM tier's behavior.
        // Inner: 18 35 (prec=53, 2 bytes)
        // Outer: 0a 02 18 35
        Ast.Document doc = Parser.parse("bf = 0");
        PxfMeta m = wellKnownHostMeta("test.Sample", "bf", 1, "org.protowire.proto.pxf.BigFloat");

        assertEquals("0a021835", hex(LiteWireWriter.encode(doc, m)));
    }

    @Test
    void bigInt_floatLiteral_throws() {
        // BigInt requires an integer literal; a float should error clearly
        // rather than silently truncating.
        Ast.Document doc = Parser.parse("n = 3.14");
        PxfMeta m = wellKnownHostMeta("test.Sample", "n", 1, "org.protowire.proto.pxf.BigInt");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LiteWireWriter.encode(doc, m));
        assertEquals(true, e.getMessage().contains("pxf.BigInt"));
        assertEquals(true, e.getMessage().contains("integer literal"));
    }
}
