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
}
