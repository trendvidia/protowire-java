// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbTest {

    public static class Endpoint {
        @ProtoField(1) String path;
        @ProtoField(2) String method;
        @ProtoField(3) int port;

        public Endpoint() {}
        public Endpoint(String path, String method, int port) {
            this.path = path; this.method = method; this.port = port;
        }
    }

    public static class Config {
        @ProtoField(1) String hostname;
        @ProtoField(2) boolean enabled;
        @ProtoField(3) List<Endpoint> endpoints = new ArrayList<>();
        @ProtoField(4) byte[] data;

        public Config() {}
    }

    @Test
    void scalarsRoundTrip() throws IOException {
        Config c = new Config();
        c.hostname = "web-01";
        c.enabled = true;
        c.data = "blob".getBytes();

        byte[] bytes = Pb.marshal(c);
        Config decoded = Pb.unmarshal(bytes, Config.class);

        assertEquals("web-01", decoded.hostname);
        assertEquals(true, decoded.enabled);
        assertArrayEquals("blob".getBytes(), decoded.data);
    }

    @Test
    void zeroValuesOmitted() throws IOException {
        Config c = new Config(); // all defaults
        byte[] bytes = Pb.marshal(c);
        // Empty list is also omitted -> empty payload.
        assertEquals(0, bytes.length);
    }

    @Test
    void repeatedMessages() throws IOException {
        Config c = new Config();
        c.hostname = "h";
        c.endpoints.add(new Endpoint("/a", "GET", 80));
        c.endpoints.add(new Endpoint("/b", "POST", 81));

        byte[] bytes = Pb.marshal(c);
        Config decoded = Pb.unmarshal(bytes, Config.class);

        assertEquals("h", decoded.hostname);
        assertEquals(2, decoded.endpoints.size());
        assertEquals("/a", decoded.endpoints.get(0).path);
        assertEquals(81, decoded.endpoints.get(1).port);
    }

    public static class BigBag {
        @ProtoField(1) BigInteger amount;
        @ProtoField(2) BigDecimal price;
    }

    @Test
    void bigNumbersRoundTrip() throws IOException {
        BigBag b = new BigBag();
        b.amount = new BigInteger("12345678901234567890123456789");
        b.price = new BigDecimal("3.14159");

        byte[] bytes = Pb.marshal(b);
        BigBag decoded = Pb.unmarshal(bytes, BigBag.class);

        assertNotNull(decoded.amount);
        assertEquals(b.amount, decoded.amount);
        assertEquals(0, b.price.compareTo(decoded.price));
        assertEquals(b.price.scale(), decoded.price.scale());
    }

    public static class WithMaps {
        @ProtoField(1) Map<String, String> tags = new HashMap<>();
        @ProtoField(2) Map<Integer, String> codes = new HashMap<>();
        @ProtoField(3) Map<String, Long> counts = new HashMap<>();
        public WithMaps() {}
    }

    @Test
    void mapsRoundTrip() throws IOException {
        WithMaps orig = new WithMaps();
        orig.tags.put("env", "prod");
        orig.tags.put("team", "platform");
        orig.tags.put("key with space", "v");
        orig.codes.put(404, "Not Found");
        orig.codes.put(500, "Internal");
        orig.counts.put("a", 1L);
        orig.counts.put("b", -7L);

        byte[] bytes = Pb.marshal(orig);
        WithMaps got = Pb.unmarshal(bytes, WithMaps.class);

        assertEquals(orig.tags, got.tags);
        assertEquals(orig.codes, got.codes);
        assertEquals(orig.counts, got.counts);
    }

    @Test
    void emptyMapEmitsNothing() throws IOException {
        WithMaps orig = new WithMaps();
        byte[] bytes = Pb.marshal(orig);
        assertEquals(0, bytes.length);

        WithMaps got = Pb.unmarshal(bytes, WithMaps.class);
        assertTrue(got.tags.isEmpty());
        assertTrue(got.codes.isEmpty());
        assertTrue(got.counts.isEmpty());
    }

    @Test
    void mapZeroValueEntriesRoundTrip() throws IOException {
        // proto3 maps: empty key/value still produce a MapEntry — the entry
        // bytes are minimal but the entry still exists. On decode, missing
        // key or value fields fall back to scalar zero.
        WithMaps orig = new WithMaps();
        // LinkedHashMap to keep iteration deterministic across JVMs.
        orig.tags = new LinkedHashMap<>();
        orig.tags.put("", "");
        orig.tags.put("k", "");

        byte[] bytes = Pb.marshal(orig);
        WithMaps got = Pb.unmarshal(bytes, WithMaps.class);

        assertEquals("", got.tags.get(""));
        assertEquals("", got.tags.get("k"));
        assertEquals(2, got.tags.size());
    }

    // -- HARDENING.md § Recursion (protowire-java#63) ------------------------

    public static class Node {
        @ProtoField(1) Node child;
        @ProtoField(2) String label;
        @ProtoField(3) Map<String, Node> kids = new HashMap<>();

        public Node() {}
    }

    private static byte[] varint(long v) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
        return o.toByteArray();
    }

    private static byte[] lengthDelimited(int field, byte[] payload) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write((field << 3) | 2);
        o.writeBytes(varint(payload.length));
        o.writeBytes(payload);
        return o.toByteArray();
    }

    /** {@code n} nested {@code child} submessages, built iteratively so the test itself never recurses. */
    private static byte[] nested(int n) {
        byte[] inner = new byte[0];
        for (int i = 0; i < n; i++) inner = lengthDelimited(1, inner);
        return inner;
    }

    private static int depthOf(Node n) {
        int d = 0;
        while (n.child != null) { n = n.child; d++; }
        return d;
    }

    private static void assertDepthRejected(byte[] wire) {
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(wire, new Node()));
        assertTrue(e.getMessage().contains("nesting depth exceeds MaxNestingDepth=100"), e.getMessage());
    }

    @Test
    void limitIsTheCrossPortDefault() {
        assertEquals(100, Pb.MAX_NESTING_DEPTH);
    }

    @Test
    void submessagesUpToTheLimitAreAccepted() throws IOException {
        // Top-level struct is depth 0 (HARDENING § Recursion, protowire#301);
        // 100 nested = 100 descents, the corpus's pb/deep-submessage-100.
        Node n = new Node();
        Pb.unmarshal(nested(100), n);
        assertEquals(100, depthOf(n));
    }

    @Test
    void submessagesPastTheLimitAreRejected() {
        assertDepthRejected(nested(101));
    }

    @Test
    void mapEntriesCountAsLevels() throws IOException {
        // 98 nested (depth 98) + map entry (99) + value struct (100): accepted.
        byte[] entry = new byte[0];
        entry = concat(lengthDelimited(1, "k".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                       lengthDelimited(2, new byte[0]));
        byte[] wire = lengthDelimited(3, entry);
        for (int i = 0; i < 98; i++) wire = lengthDelimited(1, wire);
        Node n = new Node();
        Pb.unmarshal(wire, n);
        assertEquals(98, depthOf(n));
        // One more level and the value struct sits at depth 101.
        assertDepthRejected(lengthDelimited(1, wire));
    }

    @Test
    void depthCounterSurvivesFreshInputStreams() throws IOException {
        // Every nested struct is decoded from a fresh CodedInputStream; the
        // counter must carry across them (the corpus's
        // pb/deep-submessage-200.binpb). 200 levels: rejected.
        assertDepthRejected(nested(200));
    }

    @Test
    void hundredThousandLevelsRejectedWithoutStackOverflow() {
        // assertThrows(IOException) fails on a StackOverflowError.
        assertDepthRejected(nested(100_000));
    }

    @Test
    void invalidUtf8InStringFieldIsRejected() throws IOException {
        // HARDENING.md § UTF-8: label = 0xFF 0xFE is not a proto3 string.
        byte[] bad = lengthDelimited(2, new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertThrows(IOException.class, () -> Pb.unmarshal(bad, new Node()));
        byte[] ok = lengthDelimited(2, new byte[] {(byte) 0xC3, (byte) 0xA9});
        Node n = new Node();
        Pb.unmarshal(ok, n);
        assertEquals("\u00e9", n.label);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // -- MaxNumericLiteralDigits bounds Decimal.scale (HARDENING § Mandatory limits, #81)

    public static class DecimalHolder {
        @ProtoField(1) java.math.BigDecimal d;
        public DecimalHolder() {}
    }

    /** A pxf.Decimal message with unscaled 25 and the given scale, as this module writes it. */
    private static byte[] decimalWire(int scale) throws IOException {
        byte[] msg = Pb.marshalBigDecimal(new java.math.BigDecimal(java.math.BigInteger.valueOf(25), scale));
        return lengthDelimited(1, msg);
    }

    @Test
    void decimalScaleAtTheBoundDecodes() throws IOException {
        for (int scale : new int[] {Pb.MAX_NUMERIC_LITERAL_DIGITS, -Pb.MAX_NUMERIC_LITERAL_DIGITS}) {
            DecimalHolder h = new DecimalHolder();
            Pb.unmarshal(decimalWire(scale), h);
            assertEquals(new java.math.BigDecimal(java.math.BigInteger.valueOf(25), scale), h.d, "scale " + scale);
        }
    }

    @Test
    void decimalScalePastTheBoundIsRejectedOnBothSigns() throws IOException {
        for (int scale : new int[] {Pb.MAX_NUMERIC_LITERAL_DIGITS + 1, -Pb.MAX_NUMERIC_LITERAL_DIGITS - 1,
                                    Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            byte[] wire = decimalWire(scale);
            IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(wire, new DecimalHolder()), "scale " + scale);
            assertTrue(e.getMessage().contains("MaxNumericLiteralDigits=4096"), e.getMessage());
        }
    }

    // -- what a reader must accept: both layouts, both list encodings (#77, #78)

    public static class Ints {
        @ProtoField(1) List<Integer> xs;
        @ProtoField(2) Map<String, Integer> m = new HashMap<>();
        @ProtoField(3) int n;
        public Ints() {}
    }

    @Test
    void unpackedRepeatedIntsDecode() throws IOException {
        // Three unpacked records: 08 01 08 00 08 ff…01 (-1 sign-extended).
        byte[] wire = concat(concat(varintField(1, 1), varintField(1, 0)), varintField(1, -1L));
        assertEquals(List.of(1, 0, -1), Pb.unmarshal(wire, Ints.class).xs);
    }

    @Test
    void mapEntryLackingKeyOrValueReadsAsZero() throws IOException {
        // The pre-v1.13 layout: zero-valued key / value omitted from the entry.
        byte[] onlyValue = lengthDelimited(2, varintField(2, 7));   // {"": 7}
        byte[] onlyKey = lengthDelimited(2, lengthDelimited(1, "k".getBytes(java.nio.charset.StandardCharsets.UTF_8))); // {"k": 0}
        byte[] empty = lengthDelimited(2, new byte[0]);              // {"": 0}
        Ints got = Pb.unmarshal(concat(onlyValue, onlyKey), Ints.class);
        assertEquals(7, got.m.get(""));
        assertEquals(0, got.m.get("k"));
        assertEquals(0, Pb.unmarshal(empty, Ints.class).m.get(""));
    }

    @Test
    void signedIntIsAPlainVarintByDefault() throws IOException {
        Ints plain = new Ints(); plain.n = -1;   // field 3: tag 0x18, then ten bytes
        assertEquals("18ffffffffffffffffff01", java.util.HexFormat.of().formatHex(Pb.marshal(plain)));
        assertEquals(-1, Pb.unmarshal(Pb.marshal(plain), Ints.class).n);
    }

    private static byte[] varintField(int field, long v) {
        return concat(varint((long) field << 3), varint(v));
    }

    // -- per-call limits (HARDENING § Mandatory limits, #79)

    @Test
    void maxMessageSizeIsCheckedBeforeDecoding() throws IOException {
        Ints v = new Ints(); v.xs = new ArrayList<>();
        for (int i = 0; i < 600; i++) v.xs.add(i);   // ≈ 1.2 KiB packed
        byte[] wire = Pb.marshal(v);
        assertTrue(wire.length > 1024 && wire.length < 4096, "wire is " + wire.length);
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(wire, Ints.class, UnmarshalOptions.defaults().withMaxMessageSize(1024)));
        assertTrue(e.getMessage().contains("input of " + wire.length + " bytes exceeds MaxMessageSize=1024"), e.getMessage());
        assertEquals(600, Pb.unmarshal(wire, Ints.class, UnmarshalOptions.defaults().withMaxMessageSize(4096)).xs.size());
        assertEquals(64 << 20, Pb.MAX_MESSAGE_SIZE);
        byte[] huge = new byte[Pb.MAX_MESSAGE_SIZE + 1];
        e = assertThrows(IOException.class, () -> Pb.unmarshal(huge, new Ints()));
        assertTrue(e.getMessage().contains("MaxMessageSize=" + Pb.MAX_MESSAGE_SIZE), e.getMessage());
    }

    @Test
    void repeatedCountIsRefusedBeforeTheNextElementIsAdded() throws IOException {
        Ints v = new Ints(); v.xs = new ArrayList<>();
        for (int i = 0; i < 16; i++) v.xs.add(i);
        byte[] packed = Pb.marshal(v);
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(packed, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(8)));
        assertTrue(e.getMessage().contains("repeated field exceeds MaxRepeatedCount=8"), e.getMessage());
        assertEquals(16, Pb.unmarshal(packed, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(16)).xs.size());

        byte[] unpacked = new byte[0];
        for (int i = 0; i < 16; i++) unpacked = concat(unpacked, varintField(1, i));
        byte[] u = unpacked;
        assertThrows(IOException.class, () -> Pb.unmarshal(u, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(8)));
        assertEquals(16, Pb.unmarshal(u, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(16)).xs.size());

        Ints m = new Ints();
        for (int i = 0; i < 16; i++) m.m.put("k" + i, i);
        byte[] mapWire = Pb.marshal(m);
        e = assertThrows(IOException.class, () -> Pb.unmarshal(mapWire, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(8)));
        assertTrue(e.getMessage().contains("map field exceeds MaxRepeatedCount=8"), e.getMessage());
        assertEquals(16, Pb.unmarshal(mapWire, Ints.class, UnmarshalOptions.defaults().withMaxRepeatedCount(16)).m.size());
    }

    @Test
    void nestingDepthAndDecimalScaleArePerCallToo() throws IOException {
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(nested(5), Node.class, UnmarshalOptions.defaults().withMaxNestingDepth(4)));
        assertTrue(e.getMessage().contains("MaxNestingDepth=4"), e.getMessage());
        assertEquals(5, depthOf(Pb.unmarshal(nested(5), Node.class, UnmarshalOptions.defaults().withMaxNestingDepth(5))));
        e = assertThrows(IOException.class, () -> Pb.unmarshal(decimalWire(10), DecimalHolder.class, UnmarshalOptions.defaults().withMaxNumericLiteralDigits(9)));
        assertTrue(e.getMessage().contains("MaxNumericLiteralDigits=9"), e.getMessage());
        assertEquals(10, Pb.unmarshal(decimalWire(10), DecimalHolder.class, UnmarshalOptions.defaults().withMaxNumericLiteralDigits(10)).d.scale());
        assertThrows(IllegalArgumentException.class, () -> UnmarshalOptions.defaults().with("MaxVarintBytes", 1));
        assertThrows(IllegalArgumentException.class, () -> UnmarshalOptions.defaults().withMaxRepeatedCount(0));
    }
}
