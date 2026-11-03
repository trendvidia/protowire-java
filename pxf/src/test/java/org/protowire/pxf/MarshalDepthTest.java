// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.Tree;
import org.protowire.pxf.testproto.anyback.Holder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A producer refuses a message nested more deeply than MaxNestingDepth,
 * counted as a decoder of its output counts it: every '{' or '[' is one
 * descent (HARDENING.md § Recursion, protowire#502, #167). The pb-render
 * corpus rows pin the same through check-decode.
 */
final class MarshalDepthTest {

    /** {@code n} nested {@code child} blocks. */
    private static Tree chain(int n) {
        Tree t = Tree.getDefaultInstance();
        for (int i = 0; i < n; i++) t = Tree.newBuilder().setChild(t).build();
        return t;
    }

    /** {@code n} nested {@code kids} map entries, each a '{' for the map and one for the value. */
    private static Tree kids(int n) {
        Tree t = Tree.getDefaultInstance();
        for (int i = 0; i < n; i++) t = Tree.newBuilder().putKids("k", t).build();
        return t;
    }

    /** {@code n} nested one-element {@code children} lists, each a '[' and a '{'. */
    private static Tree children(int n) {
        Tree t = Tree.getDefaultInstance();
        for (int i = 0; i < n; i++) t = Tree.newBuilder().addChildren(t).build();
        return t;
    }

    private static void assertRefused(Tree t, MarshalOptions opts) {
        PxfException e = assertThrows(PxfException.class, () -> opts.marshal(t));
        assertTrue(e.getMessage().contains("nesting depth exceeds MaxNestingDepth=" + opts.maxNestingDepth()), e.getMessage());
    }

    /** What is written reads back, with the stock decoder at the same limit. */
    private static void assertReadsBack(Tree t) {
        byte[] text = Pxf.marshal(t);
        Tree.Builder b = Tree.newBuilder();
        Pxf.unmarshal(text, b);
        assertEquals(t, b.build());
    }

    @Test
    void submessagesUpToTheLimitAreWritten() {
        assertReadsBack(chain(100));
        assertRefused(chain(101), MarshalOptions.defaults());
    }

    @Test
    void aMapEntryWithAMessageValueIsTwoDescents() {
        assertReadsBack(kids(50));
        // A child at the deepest point of 50 map levels is the 101st descent.
        assertRefused(Tree.newBuilder().setChild(kids(50)).build(), MarshalOptions.defaults());
    }

    @Test
    void aListOfMessagesIsTwoDescents() {
        assertReadsBack(children(50));
        assertRefused(Tree.newBuilder().setChild(children(50)).build(), MarshalOptions.defaults());
    }

    @Test
    void theLimitIsPerCall() {
        MarshalOptions deep = MarshalOptions.defaults().withMaxNestingDepth(200);
        String text = new String(deep.marshal(chain(150)), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("child {"), text);
        assertRefused(chain(201), deep);
        assertRefused(chain(11), MarshalOptions.defaults().withMaxNestingDepth(10));
        assertThrows(IllegalArgumentException.class, () -> MarshalOptions.defaults().withMaxNestingDepth(0));
    }

    @Test
    void anAnyWhoseBodyIsPastTheLimitStaysRaw() {
        // payload's block is one descent, so a 100-level body would be 101.
        // It keeps type_url and value, which a reader at the limit reads.
        TypeResolver resolver = url -> url.endsWith("test.v1.Tree") ? Tree.getDescriptor() : null;
        MarshalOptions opts = MarshalOptions.defaults().withTypeResolver(resolver);
        Holder raw = Holder.newBuilder().setPayload(Any.pack(chain(100))).build();
        String text = new String(opts.marshal(raw), StandardCharsets.UTF_8);
        assertTrue(text.contains("type_url = ") && !text.contains("@type"), text);
        Holder body = Holder.newBuilder().setPayload(Any.pack(chain(99))).build();
        text = new String(opts.marshal(body), StandardCharsets.UTF_8);
        assertTrue(text.contains("@type = "), text);
    }
}
