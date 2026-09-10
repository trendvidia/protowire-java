// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.Tree;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDENING.md § Recursion and § UTF-8 for the descriptor-driven decoder
 * (protowire-java#63, #80). Depth counts every {@code decodeFields} /
 * {@code decodeList} / {@code decodeMap} entry as one descent from a root
 * at depth 0, exactly like protowire-go's {@code decode_fast.go} and the
 * corpus rows {@code pxf/deep-nesting-100} / {@code -101}: 100 nested
 * submessages are accepted and the 101st is rejected.
 */
class FastDecoderLimitsTest {

    private static String nest(int n) {
        return "child{".repeat(n) + "}".repeat(n);
    }

    private static Tree decode(String doc) {
        Tree.Builder b = Tree.newBuilder();
        Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static void assertDepthRejected(String doc) {
        PxfException e = assertThrows(PxfException.class, () -> decode(doc));
        assertTrue(e.getMessage().contains("nesting depth exceeds MaxNestingDepth=100"), e.getMessage());
    }

    private static int depthOf(Tree t) {
        int d = 0;
        while (t.hasChild()) { t = t.getChild(); d++; }
        return d;
    }

    @Test
    void submessagesUpToTheLimitAreAccepted() {
        assertEquals(100, depthOf(decode(nest(100))));
    }

    @Test
    void submessagesPastTheLimitAreRejected() {
        assertDepthRejected(nest(101));
    }

    @Test
    void listElementsCountAsLevels() {
        // 98 blocks (depth 98) + list (99) + element block (100): accepted.
        Tree t = decode("child{".repeat(98) + "children = [ {} ]" + "}".repeat(98));
        assertEquals(98, depthOf(t));
        assertDepthRejected("child{".repeat(99) + "children = [ {} ]" + "}".repeat(99));
    }

    @Test
    void mapEntriesCountAsLevels() {
        // 98 blocks (depth 98) + map (99) + value block (100): accepted.
        decode("child{".repeat(98) + "kids = { a: {} }" + "}".repeat(98));
        assertDepthRejected("child{".repeat(99) + "kids = { a: {} }" + "}".repeat(99));
    }

    @Test
    void depthIsRestoredForSiblings() {
        // First subtree reaches depth 100; the sibling list element then
        // reaches depth 100 on its own count (list 1 + block 1 + 98), not
        // 100 + 100.
        Tree t = decode(nest(100) + "\n" + "children = [ {" + nest(98) + "} ]");
        assertEquals(100, depthOf(t));
        assertEquals(1, t.getChildrenCount());
        assertEquals(98, depthOf(t.getChildren(0)));
    }

    @Test
    void hundredThousandLevelsRejectedWithoutStackOverflow() {
        assertDepthRejected(nest(100_000));
    }

    @Test
    void invalidUtf8EscapeInStringFieldIsRejected() {
        PxfException e = assertThrows(PxfException.class, () -> decode("label = \"\\xFF\\xFE\""));
        assertTrue(e.getMessage().contains("invalid UTF-8 in string literal"), e.getMessage());
        assertEquals("\u00e9", decode("label = \"\\xC3\\xA9\"").getLabel());
    }
}
