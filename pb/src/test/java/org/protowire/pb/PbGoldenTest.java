// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Pb} measured against the reference, not against itself (#77,
 * STABILITY.md promise 2): every struct here has a twin in
 * {@code src/test/golden/main.go}, and {@code resources/golden/pb-go-golden.txt}
 * holds the bytes protowire-go's {@code encoding/pb} wrote for it. The
 * encode direction is compared byte for byte; the decode direction reads
 * Go's bytes back to the same values. Round trips inside this module
 * passed for years while every non-zero integer was zigzag-encoded,
 * because both directions were wrong the same way.
 */
class PbGoldenTest {

    private static final Map<String, String> GOLDEN = load();

    private static Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        try (InputStream in = PbGoldenTest.class.getResourceAsStream("/golden/pb-go-golden.txt")) {
            assertNotNull(in, "golden file missing");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] kv = line.split("\t");
                m.put(kv[0], kv[1]);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
    private static byte[] golden(String name) {
        String h = GOLDEN.get(name);
        assertNotNull(h, "no golden for " + name);
        return HexFormat.of().parseHex(h);
    }

    private static void assertEncodesAs(String name, Object v) throws IOException {
        assertEquals(GOLDEN.get(name), hex(Pb.marshal(v)), name + ": bytes differ from protowire-go");
    }

    // -- the #77 repro -------------------------------------------------------

    public static class Ints {
        @ProtoField(1) long a;
        @ProtoField(2) Long b;
        @ProtoField(3) int c;
        @ProtoField(4) Map<String, Long> m = new LinkedHashMap<>();
        @ProtoField(5) Map<String, Integer> mi = new LinkedHashMap<>();
        public Ints() {}
    }

    @Test
    void negativeOneIsTenBytesSignExtended() throws IOException {
        Ints v = new Ints();
        v.a = -1; v.b = -1L; v.c = -1; v.m.put("k", -1L); v.mi.put("k", -1);
        assertEncodesAs("Ints", v);
        assertTrue(GOLDEN.get("Ints").startsWith("08ffffffffffffffffff01"));

        Ints back = Pb.unmarshal(golden("Ints"), Ints.class);
        assertEquals(-1L, back.a, "used to read as Long.MIN_VALUE");
        assertEquals(-1L, back.b);
        assertEquals(-1, back.c, "used to read as 0");
        assertEquals(Map.of("k", -1L), back.m);
        assertEquals(Map.of("k", -1), back.mi);
    }

    public static class Age { @ProtoField(1) int age; public Age() {} }

    // Zigzag and plain varint agree on exactly one value, zero: 30 is 1e
    // everywhere else and was 3c here.
    @Test
    void thirtyIsOneByte() throws IOException {
        Age v = new Age(); v.age = 30;
        assertEncodesAs("Age", v);
        assertEquals("081e", GOLDEN.get("Age"));
        assertEquals(30, Pb.unmarshal(golden("Age"), Age.class).age);
    }

    public static class MinLong { @ProtoField(1) long v; public MinLong() {} }

    @Test
    void longMinValue() throws IOException {
        MinLong v = new MinLong(); v.v = Long.MIN_VALUE;
        assertEncodesAs("MinLong", v);
        assertEquals(Long.MIN_VALUE, Pb.unmarshal(golden("MinLong"), MinLong.class).v);
    }

    // -- the opt-in ----------------------------------------------------------

    public static class Zigzag {
        @ProtoField(value = 1, zigzag = true) long d;
        @ProtoField(value = 2, zigzag = true) int e;
        public Zigzag() {}
    }

    @Test
    void zigzagOptInMirrorsGoTagOption() throws IOException {
        Zigzag v = new Zigzag(); v.d = -1; v.e = -2;
        assertEncodesAs("Zigzag", v);
        assertEquals("08011003", GOLDEN.get("Zigzag"));
        Zigzag back = Pb.unmarshal(golden("Zigzag"), Zigzag.class);
        assertEquals(-1L, back.d);
        assertEquals(-2, back.e);
    }

    // -- packed repeated numerics -------------------------------------------

    public static class Packed {
        @ProtoField(1) List<Integer> xs;
        @ProtoField(2) List<Long> ys;
        @ProtoField(3) List<Boolean> bs;
        @ProtoField(4) List<Double> ds;
        @ProtoField(5) List<Float> fs;
        public Packed() {}
    }

    @Test
    void numericListsArePackedZerosIncluded() throws IOException {
        Packed v = new Packed();
        v.xs = List.of(1, 0, -1); v.ys = List.of(300L); v.bs = List.of(true, false);
        v.ds = List.of(1.5); v.fs = List.of(-2.5f);
        assertEncodesAs("Packed", v);
        Packed back = Pb.unmarshal(golden("Packed"), Packed.class);
        assertEquals(List.of(1, 0, -1), back.xs);
        assertEquals(List.of(300L), back.ys);
        assertEquals(List.of(true, false), back.bs);
        assertEquals(List.of(1.5), back.ds);
        assertEquals(List.of(-2.5f), back.fs);
    }

    // -- map entries always carry key and value (#78) ------------------------

    public static class ZeroMapEntry {
        @ProtoField(1) Map<String, String> m = new LinkedHashMap<>();
        @ProtoField(2) Map<String, Integer> z = new LinkedHashMap<>();
        @ProtoField(3) Map<Integer, String> k = new LinkedHashMap<>();
        public ZeroMapEntry() {}
    }

    @Test
    void zeroValuedMapEntryFieldsAreWritten() throws IOException {
        ZeroMapEntry v = new ZeroMapEntry();
        v.m.put("", ""); v.z.put("zero", 0); v.k.put(0, "v");
        assertEncodesAs("ZeroMapEntry", v);
        assertTrue(GOLDEN.get("ZeroMapEntry").startsWith("0a040a001200"), "both fields, both empty");
        ZeroMapEntry back = Pb.unmarshal(golden("ZeroMapEntry"), ZeroMapEntry.class);
        assertEquals(Map.of("", ""), back.m);
        assertEquals(Map.of("zero", 0), back.z);
        assertEquals(Map.of(0, "v"), back.k);
    }

    // -- pxf.Decimal.scale is a plain varint ---------------------------------

    public static class Decimal { @ProtoField(1) BigDecimal d; public Decimal() {} }

    @Test
    void decimalScaleIsAPlainVarint() throws IOException {
        Decimal v = new Decimal(); v.d = new BigDecimal("3.1415");
        assertEncodesAs("Decimal", v);
        assertTrue(GOLDEN.get("Decimal").endsWith("1004"), "scale 4, not zigzag 8");
        assertEquals(0, new BigDecimal("3.1415").compareTo(Pb.unmarshal(golden("Decimal"), Decimal.class).d));
    }

    // -- nested messages and non-packed lists ---------------------------------

    public static class Leaf {
        @ProtoField(1) String name;
        @ProtoField(2) int n;
        public Leaf() {}
        Leaf(String name, int n) { this.name = name; this.n = n; }
    }

    public static class Nested {
        @ProtoField(1) String s;
        @ProtoField(2) List<Leaf> ls;
        @ProtoField(3) List<String> ss;
        public Nested() {}
    }

    // A zero element is its zero record (an empty message, an empty string),
    // so the list length survives; a negative int inside a nested message
    // is sign-extended like any other.
    @Test
    void nestedAndUnpackedElementsMatch() throws IOException {
        Nested v = new Nested();
        v.s = "x"; v.ls = List.of(new Leaf(), new Leaf("l", -3)); v.ss = List.of("", "s");
        assertEncodesAs("Nested", v);
        Nested back = Pb.unmarshal(golden("Nested"), Nested.class);
        assertEquals(2, back.ls.size());
        assertEquals(-3, back.ls.get(1).n);
        assertEquals(List.of("", "s"), back.ss);
    }

    // -- the gate's wire vector (#78) ----------------------------------------

    public static final class Envelope { @ProtoField(4) AppError error; public Envelope() {} }
    public static final class AppError { @ProtoField(5) Map<String, String> metadata = new LinkedHashMap<>(); public AppError() {} }

    // testdata/envelope/zero-map-entry.expected.hex in the spec repo: the
    // bytes protoc --encode and protobuf-go write for
    // `error { metadata { key: "" value: "" } }` — two independent oracles,
    // not port-to-port agreement. This port wrote 22022a00 (both fields
    // omitted) before #78.
    @Test
    void zeroMapEntryVectorMatchesTheSpecGolden() throws IOException {
        Envelope env = new Envelope();
        env.error = new AppError();
        env.error.metadata.put("", "");
        assertEquals("22062a040a001200", hex(Pb.marshal(env)));
    }
}
