package org.protowire.pxf.android;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;
import org.protowire.pxf.SimplePxfRegistry;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip checks for {@link LiteWireReader}. Each test follows the
 * pattern: <em>start with PXF text → encode to wire bytes → decode back to
 * AST → format back to text → encode again → assert second-pass bytes
 * equal first-pass bytes</em>. Round-trip equivalence is the contract;
 * the intermediate text representation may not be character-identical
 * (whitespace, field ordering) but its semantic content must be.
 */
final class LiteWireReaderTest {

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    /**
     * The full LiteWireWriter ↔ LiteWireReader round-trip: take some PXF text,
     * encode → bytes_1, decode bytes_1 → AST_2, format AST_2 → text_2, parse
     * text_2 → AST_3, encode AST_3 → bytes_3, assert bytes_3 == bytes_1.
     */
    private static void assertRoundTrip(String pxfText, PxfMeta meta) {
        assertRoundTrip(pxfText, meta, /* registry */ null);
    }

    private static void assertRoundTrip(String pxfText, PxfMeta meta, PxfRegistry registry) {
        Ast.Document parsed1 = Parser.parse(pxfText);
        byte[] bytes1 = registry == null
            ? LiteWireWriter.encode(parsed1, meta)
            : LiteWireWriter.encode(parsed1, meta, registry);

        Ast.Document decoded = registry == null
            ? LiteWireReader.toAst(bytes1, meta)
            : LiteWireReader.toAst(bytes1, meta, registry);

        String reformatted = Format.formatDocument(decoded);
        Ast.Document parsed2 = Parser.parse(reformatted);

        byte[] bytes2 = registry == null
            ? LiteWireWriter.encode(parsed2, meta)
            : LiteWireWriter.encode(parsed2, meta, registry);

        assertEquals(hex(bytes1), hex(bytes2),
            "round-trip wire bytes differ\nfirst-pass text:\n" + pxfText +
            "\ndecoded → reformatted:\n" + reformatted);
    }

    // --- helpers (mirror writer-test patterns) ----------------------------

    private static PxfMeta scalarMeta(String name, int num, int kind) {
        return new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of(name, num); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(num, kind); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
    }

    @Test
    void scalarString_roundTrip() {
        assertRoundTrip("name = \"Alice\"", scalarMeta("name", 1, 9));
    }

    @Test
    void scalarInt32_roundTrip() {
        assertRoundTrip("age = 30", scalarMeta("age", 1, 5));
    }

    @Test
    void scalarBool_roundTrip() {
        assertRoundTrip("flag = true", scalarMeta("flag", 1, 8));
    }

    @Test
    void scalarBytes_roundTrip() {
        // base64 of 0xDE 0xAD 0xBE 0xEF
        assertRoundTrip("data = b\"3q2+7w==\"", scalarMeta("data", 1, 12));
    }

    @Test
    void scalarSint32_roundTrip() {
        assertRoundTrip("n = -1", scalarMeta("n", 1, 17));
    }

    @Test
    void repeatedString_roundTrip() {
        PxfMeta m = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("aliases", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 9); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(1); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
        assertRoundTrip("aliases = [\"a\", \"bb\", \"ccc\"]", m);
    }

    @Test
    void repeatedInt32_packed_roundTrip() {
        PxfMeta m = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("lucky", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 5); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(1); }
            @Override public Set<Integer>          packedFields()       { return Set.of(1); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
        assertRoundTrip("lucky = [1, 2, 300]", m);
    }

    @Test
    void nestedMessage_roundTrip() {
        // Inner: { s: string=1 }, Outer: { inner: Inner=1 }
        PxfMeta innerMeta = new PxfMeta() {
            @Override public String                fullName()           { return "test.Inner"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("s", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 9); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
        PxfMeta outerMeta = new PxfMeta() {
            @Override public String                fullName()           { return "test.Outer"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("inner", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 11 /* MESSAGE */); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(1, "test.Inner"); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfMeta> nestedMetas()        { return Map.of(1, innerMeta); }
        };
        assertRoundTrip("inner { s = \"hi\" }", outerMeta);
    }

    @Test
    void enum_byIdentifier_roundTrip() {
        PxfEnum statusEnum = new PxfEnum() {
            @Override public String fullName() { return "test.Status"; }
            @Override public Map<String, Integer> values() {
                return Map.of("STATUS_UNKNOWN", 0, "STATUS_ACTIVE", 1);
            }
            @Override public Map<Integer, String> names() {
                return Map.of(0, "STATUS_UNKNOWN", 1, "STATUS_ACTIVE");
            }
        };
        PxfMeta sampleMeta = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("status", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 14 /* ENUM */); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(1, "test.Status"); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfEnum> enumMetas()          { return Map.of(1, statusEnum); }
        };
        assertRoundTrip("status = STATUS_ACTIVE", sampleMeta);
    }

    @Test
    void map_stringToString_roundTrip() {
        // Synthetic LabelsEntry { string key = 1; string value = 2; }
        PxfMeta entryMeta = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample.LabelsEntry"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("key", 1, "value", 2); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 9, 2, 9); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
        };
        PxfMeta hostMeta = new PxfMeta() {
            @Override public String                fullName()           { return "test.Sample"; }
            @Override public Map<String, Integer>  fieldNumbers()       { return Map.of("labels", 1); }
            @Override public Map<Integer, Integer> fieldKinds()         { return Map.of(1, 11); }
            @Override public Map<Integer, Integer> wireTypes()          { return Map.of(); }
            @Override public Set<Integer>          repeatedFields()     { return Set.of(1); }
            @Override public Set<Integer>          packedFields()       { return Set.of(); }
            @Override public Map<Integer, String>  messageTypes()       { return Map.of(1, "test.Sample.LabelsEntry"); }
            @Override public Map<Integer, String>  enumTypes()          { return Map.of(); }
            @Override public Set<Integer>          requiredFields()     { return Set.of(); }
            @Override public Map<Integer, String>  defaults()           { return Map.of(); }
            @Override public int                   sbeTemplateId()      { return -1; }
            @Override public Map<Integer, Integer> sbeFieldLengths()    { return Map.of(); }
            @Override public Map<Integer, String>  sbeFieldEncodings()  { return Map.of(); }
            @Override public Map<Integer, String>  oneofOf()            { return Map.of(); }
            @Override public Map<Integer, PxfMeta> nestedMetas()        { return Map.of(1, entryMeta); }
            @Override public Set<Integer>          mapFields()          { return Set.of(1); }
        };
        assertRoundTrip("labels { \"env\": \"prod\" }", hostMeta);
    }
}
