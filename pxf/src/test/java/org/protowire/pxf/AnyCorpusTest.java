// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import any.v1.Any.Holder;
import any.v1.Any.Leaf;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/any/} corpus (#100), vendored under
 * {@code src/test/resources/corpus/any/} from trendvidia/protowire 234cf29,
 * through the schema-less parse and a decoder bound to {@code any.v1.Holder}
 * with a resolver that maps a URL to the message named after its last
 * {@code /}. The verdict table is the corpus README's.
 */
class AnyCorpusTest {

    private static final TypeResolver RESOLVER = url -> {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.equals(Leaf.getDescriptor().getFullName()) ? Leaf.getDescriptor() : null;
    };

    private static final UnmarshalOptions OPTS = UnmarshalOptions.defaults().withTypeResolver(RESOLVER);

    /** The reject fixtures a parser accepts: it cannot tell which blocks are Any, or resolve a URL. */
    private static final Set<String> PARSE_ACCEPTS = Set.of("err-not-any.pxf", "err-unresolved.pxf");

    private static Holder decode(byte[] data) {
        Holder.Builder b = Holder.newBuilder();
        OPTS.unmarshal(data, b);
        return b.build();
    }

    private static Leaf unpack(com.google.protobuf.Any any) throws InvalidProtocolBufferException {
        return Leaf.parseFrom(any.getValue());
    }

    @Test
    void everyFixtureMeetsItsVerdict() throws IOException {
        List<CorpusFixtures.Fixture> all = CorpusFixtures.load("any");
        assertEquals(11, all.size());
        Descriptor d = Holder.getDescriptor();
        for (CorpusFixtures.Fixture f : all) {
            if (f.rejects()) {
                assertThrows(PxfException.class, () -> OPTS.unmarshal(f.data(), d), f.name());
                assertThrows(PxfException.class, () -> OPTS.unmarshalFull(f.data(), Holder.newBuilder()), f.name());
            } else {
                OPTS.unmarshal(f.data(), d);
                OPTS.unmarshalFull(f.data(), Holder.newBuilder());
            }
            if (f.rejects() && !PARSE_ACCEPTS.contains(f.name())) {
                assertThrows(PxfException.class, () -> Pxf.parse(f.data()), f.name());
            } else {
                assertNotNull(Pxf.parse(f.data()), f.name());
            }
        }
    }

    private static Map<String, byte[]> fixtures() throws IOException {
        Map<String, byte[]> out = new java.util.HashMap<>();
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("any")) out.put(f.name(), f.data());
        return out;
    }

    @Test
    void acceptedBlocksPackTheirBody() throws Exception {
        Map<String, byte[]> by = fixtures();

        Holder h = decode(by.get("block.pxf"));
        assertEquals("any.v1.Leaf", h.getPayload().getTypeUrl());
        assertEquals(Leaf.newBuilder().setTag("t").setN(7).build(), unpack(h.getPayload()));

        assertEquals("t", unpack(decode(by.get("assignment.pxf")).getPayload()).getTag());

        assertEquals("type.googleapis.com/any.v1.Leaf", decode(by.get("url-verbatim.pxf")).getPayload().getTypeUrl());

        h = decode(by.get("list-element.pxf"));
        assertEquals(2, h.getAnysCount());
        assertEquals("a", unpack(h.getAnys(0)).getTag());
        assertEquals(2, unpack(h.getAnys(1)).getN());

        assertEquals("t", unpack(decode(by.get("map-value.pxf")).getByKeyOrThrow("k")).getTag());

        h = decode(by.get("plain-fields.pxf"));
        assertEquals("any.v1.Leaf", h.getPayload().getTypeUrl());
        assertEquals("t", unpack(h.getPayload()).getTag());
    }

    @Test
    void aTypeEntryWithoutAResolverIsRejected() throws IOException {
        byte[] data = fixtures().get("block.pxf");
        PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(data, Holder.newBuilder()));
        assertTrue(e.getMessage().contains("no TypeResolver"), e.getMessage());
    }

    @Test
    void plainFieldsNeedNoResolver() throws IOException {
        Holder.Builder b = Holder.newBuilder();
        Pxf.unmarshal(fixtures().get("plain-fields.pxf"), b);
        assertEquals("any.v1.Leaf", b.getPayload().getTypeUrl());
    }

    @Test
    void aTypeEntryIsNotADocumentEntry() {
        assertThrows(PxfException.class, () -> Pxf.parse("a = 1\n@type = \"x\"\n"));
    }

    @Test
    void theFormatterKeepsTheTypeEntry() throws IOException {
        byte[] data = fixtures().get("block.pxf");
        String text = new String(Pxf.formatDocument(Pxf.parse(data)), StandardCharsets.UTF_8);
        assertTrue(text.contains("@type = \"any.v1.Leaf\""), text);
        assertEquals(decode(data), decode(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theEncoderWritesWhatTheDecoderReads() throws Exception {
        Holder h = decode(fixtures().get("block.pxf"));
        byte[] out = MarshalOptions.defaults().withTypeResolver(RESOLVER).marshal(h);
        assertTrue(new String(out, StandardCharsets.UTF_8).contains("@type = \"any.v1.Leaf\""));
        assertEquals(h, decode(out));
        assertFalse(h.getPayload().getValue().isEmpty());
    }
}
