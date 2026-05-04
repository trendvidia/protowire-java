package org.protowire.pxf.android;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;
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
}
