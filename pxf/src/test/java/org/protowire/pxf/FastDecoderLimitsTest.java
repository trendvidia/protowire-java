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
 * (protowire-java#63). Depth counts every {@code decodeFields} /
 * {@code decodeList} / {@code decodeMap} entry, the top-level call being
 * depth 1, exactly like protowire-go's {@code decode_fast.go}: 99 nested
 * submessages are accepted (depth 100) and the 100th is rejected.
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
        assertEquals(99, depthOf(decode(nest(99))));
    }

    @Test
    void submessagesPastTheLimitAreRejected() {
        assertDepthRejected(nest(100));
    }

    @Test
    void listElementsCountAsLevels() {
        // 97 blocks (depth 98) + list (99) + element block (100): accepted.
        Tree t = decode("child{".repeat(97) + "children = [ {} ]" + "}".repeat(97));
        assertEquals(97, depthOf(t));
        assertDepthRejected("child{".repeat(98) + "children = [ {} ]" + "}".repeat(98));
    }

    @Test
    void mapEntriesCountAsLevels() {
        // 97 blocks (depth 98) + map (99) + value block (100): accepted.
        decode("child{".repeat(97) + "kids = { a: {} }" + "}".repeat(97));
        assertDepthRejected("child{".repeat(98) + "kids = { a: {} }" + "}".repeat(98));
    }

    @Test
    void depthIsRestoredForSiblings() {
        // First subtree reaches depth 99; the sibling list element then
        // reaches depth 100 on its own count, not 99 + 100.
        Tree t = decode(nest(98) + "\n" + "children = [ {" + nest(97) + "} ]");
        assertEquals(98, depthOf(t));
        assertEquals(1, t.getChildrenCount());
        assertEquals(97, depthOf(t.getChildren(0)));
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
