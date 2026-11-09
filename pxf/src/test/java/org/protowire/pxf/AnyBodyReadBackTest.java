// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.protowire.pxf.testproto.anyback.Defaulted;
import org.protowire.pxf.testproto.anyback.Holder;
import org.protowire.pxf.testproto.anyback.Leaf;
import org.protowire.pxf.testproto.anyback.Required;
import org.protowire.pxf.testproto.anyback.RequiredOptional;
import org.protowire.pxf.testproto.anyback.Rich;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Any is written as {@code @type} and a body only when reading that body
 * gives back its value, byte for byte (#150). The cases are those of
 * protowire-go's any_body_readback_test.go.
 */
final class AnyBodyReadBackTest {
    private static final Map<String, Descriptor> TYPES = Map.of(
        "anyback.v1.Holder", Holder.getDescriptor(),
        "anyback.v1.Leaf", Leaf.getDescriptor(),
        "anyback.v1.Rich", Rich.getDescriptor(),
        "anyback.v1.Defaulted", Defaulted.getDescriptor(),
        "anyback.v1.Required", Required.getDescriptor(),
        "anyback.v1.RequiredOptional", RequiredOptional.getDescriptor());
    private static final TypeResolver RESOLVER = TYPES::get;
    private static final UnmarshalOptions READ = UnmarshalOptions.defaults().withTypeResolver(RESOLVER);

    private record Case(String name, String type, byte[] value, boolean body) {
        @Override public String toString() { return name; }
    }

    private static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7fL) != 0) {
            o.write((int) (v & 0x7f) | 0x80);
            v >>>= 7;
        }
        o.write((int) v);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] vf(int num, long v) {
        return cat(varint(num << 3), varint(v));
    }

    private static byte[] lf(int num, byte[] body) {
        return cat(varint((num << 3) | 2), varint(body.length), body);
    }

    private static byte[] str(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] entry(String k, long v) {
        return lf(4, cat(lf(1, str(k)), vf(2, v)));
    }

    static List<Case> cases() {
        byte[] tagT = lf(1, str("t"));
        byte[] n1 = vf(2, 1);
        byte[] unknown = vf(101, 1);
        String leaf = "anyback.v1.Leaf", rich = "anyback.v1.Rich";
        return List.of(
            // What a body can say keeps the body form.
            new Case("canonical", leaf, cat(tagT, n1), true),
            new Case("empty", leaf, new byte[0], true),
            new Case("timestamp", rich, lf(1, vf(1, 1700000000)), true),
            new Case("wrapper", rich, lf(2, vf(1, 7)), true),
            new Case("wrapper holding zero", rich, lf(2, new byte[0]), true),
            new Case("sorted map", rich, cat(entry("a", 1), entry("b", 2)), true),
            new Case("packed list", rich, lf(5, new byte[] {1, 2}), true),
            new Case("optional zero", rich, vf(6, 0), true),
            new Case("default field set", "anyback.v1.Defaulted", lf(1, str("x")), true),
            new Case("required field set", "anyback.v1.Required", vf(1, 3), true),
            // Unknown fields, at the top and one level down.
            new Case("unknown field", leaf, cat(tagT, unknown), false),
            new Case("fields out of order", leaf, cat(n1, tagT), false),
            new Case("unknown in submessage", rich, lf(3, cat(tagT, unknown)), false),
            new Case("unknown in list element", rich, lf(8, cat(tagT, unknown)), false),
            new Case("unknown in wrapper", rich, lf(2, cat(vf(1, 7), unknown)), false),
            new Case("unknown in timestamp", rich, lf(1, cat(vf(1, 5), unknown)), false),
            // Bytes a decoder wouldn't write back.
            new Case("explicit zero", leaf, vf(2, 0), false),
            new Case("long varint", leaf, new byte[] {0x10, (byte) 0x81, 0x00}, false),
            new Case("unpacked list", rich, cat(vf(5, 1), vf(5, 2)), false),
            new Case("map out of order", rich, cat(entry("b", 2), entry("a", 1)), false),
            new Case("repeated singular field", leaf, cat(vf(2, 1), vf(2, 2)), false),
            // A literal can't hold it.
            new Case("timestamp past year 9999", rich, lf(1, vf(1, 1L << 40)), false),
            // An unset annotated field is written so that it reads back
            // (protowire-go#259): as its zero, so the body is written.
            new Case("default unset", "anyback.v1.Defaulted", vf(2, 1), true),
            new Case("required unset", "anyback.v1.Required", lf(2, str("s")), true),
            // A reader's annotation rules would refuse the body: no text says
            // an optional field is unset, and marshal refuses to leave it out.
            new Case("required optional unset", "anyback.v1.RequiredOptional", lf(2, str("s")), false),
            // Never unpacked at all.
            new Case("unresolved", "anyback.v1.Nope", tagT, false),
            new Case("unparsable", leaf, new byte[] {(byte) 0xff}, false),
            new Case("map entry with a wrong field type", rich, lf(4, cat(lf(1, str("a")), vf(1, 5))), false));
    }

    private static Holder holder(String type, byte[] value) {
        return Holder.newBuilder()
            .setPayload(Any.newBuilder().setTypeUrl(type).setValue(ByteString.copyFrom(value)))
            .build();
    }

    /** Marshals {@code m}, reads it with both decoders, and returns the document. */
    private static String assertReadsBack(Holder m, TypeResolver resolver) throws Exception {
        byte[] out = MarshalOptions.defaults().withTypeResolver(resolver).marshal(m);
        String text = new String(out, StandardCharsets.UTF_8);
        Holder.Builder plain = Holder.newBuilder();
        READ.unmarshal(out, plain);
        assertEquals(m, plain.build(), text);
        Holder.Builder full = Holder.newBuilder();
        READ.unmarshalFull(out, full);
        assertEquals(m, full.build(), text);
        return text;
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aBodyIsWrittenOnlyWhenItReadsBack(Case c) throws Exception {
        String text = assertReadsBack(holder(c.type, c.value), RESOLVER);
        assertEquals(c.body, text.contains("@type"), text);
    }

    /** {@code n} Anys, each holding a Holder, around a leaf. */
    private static Holder chain(int n) {
        Holder m = holder("anyback.v1.Leaf", lf(1, str("t")));
        for (int i = 1; i < n; i++) m = holder("anyback.v1.Holder", m.toByteArray());
        return m;
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void aChainStopsAtTheDepthLimit() throws Exception {
        int[][] table = {{1, 1}, {99, 99}, {100, 100}, {101, 99}, {300, 99}};
        for (int[] row : table) {
            String text = assertReadsBack(chain(row[0]), RESOLVER);
            assertEquals(row[1], count(text, "@type"), row[0] + " Anys");
        }
    }

    @Test
    void aChainPastTheLimitIsNotWalked() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TypeResolver counting = url -> {
            calls.incrementAndGet();
            return RESOLVER.findMessageByUrl(url);
        };
        MarshalOptions.defaults().withTypeResolver(counting).marshal(chain(150));
        int at150 = calls.getAndSet(0);
        MarshalOptions.defaults().withTypeResolver(counting).marshal(chain(1000));
        assertEquals(at150, calls.get());
    }

    @Test
    void aFailedBodyLeavesTheFieldsAfterItIntact() throws Exception {
        Holder m = holder("anyback.v1.Leaf", cat(lf(1, str("t")), vf(101, 1))).toBuilder()
            .setNext(Holder.getDefaultInstance())
            .build();
        String text = assertReadsBack(m, RESOLVER);
        assertTrue(text.contains("type_url = \"anyback.v1.Leaf\""), text);
        assertTrue(text.contains("next {"), text);
    }
}
