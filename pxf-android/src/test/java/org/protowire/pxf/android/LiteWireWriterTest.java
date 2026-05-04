package org.protowire.pxf.android;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;

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
}
