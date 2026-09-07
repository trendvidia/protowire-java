// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDENING.md § Recursion for the AST parser: {@code {} / {@code [}
 * nesting is capped at {@link Limits#MAX_NESTING_DEPTH}. Top-level entries
 * are depth 0 (protowire-go {@code parser.go}), so 100 nested blocks are
 * accepted and the 101st is rejected — with a {@link PxfException}, never
 * a {@link StackOverflowError} (protowire-java#63).
 */
class ParserLimitsTest {

    private static String blocks(int n) {
        return "a{".repeat(n) + "}".repeat(n);
    }

    private static String lists(int n) {
        return "a = " + "[".repeat(n) + "]".repeat(n);
    }

    private static void assertDepthRejected(String doc) {
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse(doc));
        assertTrue(e.getMessage().contains("nesting depth exceeds MaxNestingDepth=100"), e.getMessage());
    }

    @Test
    void limitIsTheCrossPortDefault() {
        assertEquals(100, Limits.MAX_NESTING_DEPTH);
    }

    @Test
    void blocksAtTheLimitAreAccepted() {
        assertNotNull(Parser.parse(blocks(100)));
    }

    @Test
    void blocksPastTheLimitAreRejected() {
        assertDepthRejected(blocks(101));
    }

    @Test
    void listsAtTheLimitAreAccepted() {
        assertNotNull(Parser.parse(lists(100)));
    }

    @Test
    void listsPastTheLimitAreRejected() {
        assertDepthRejected(lists(101));
    }

    @Test
    void blocksAndListsShareOneCounter() {
        // 50 blocks + a list nested 50 deep = depth 100: accepted.
        String ok = "a{".repeat(50) + "v = " + "[".repeat(50) + "]".repeat(50) + "}".repeat(50);
        assertNotNull(Parser.parse(ok));
        // One more list level tips it over.
        String bad = "a{".repeat(50) + "v = " + "[".repeat(51) + "]".repeat(51) + "}".repeat(50);
        assertDepthRejected(bad);
    }

    @Test
    void depthIsRestoredForSiblings() {
        // Two sibling subtrees each at the limit; the second must not inherit
        // the first's count.
        assertNotNull(Parser.parse(blocks(100) + "\n" + blocks(100)));
    }

    @Test
    void hundredThousandLevelsRejectedWithoutStackOverflow() {
        // The corpus's pxf/deep-nesting-100000.pxf: rejected by the counter,
        // long before the JVM stack would give out. assertThrows would fail
        // on a StackOverflowError since it is not a PxfException.
        assertDepthRejected(blocks(100_000));
        assertDepthRejected(lists(100_000));
    }
}
