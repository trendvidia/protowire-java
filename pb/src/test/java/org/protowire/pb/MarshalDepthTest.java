// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pb.marshal refuses a value nested more deeply than MaxNestingDepth,
 * counted as a decoder counts it: every submessage or map entry is one
 * descent (HARDENING.md § Recursion, protowire#502, #167), as protowire-go's
 * pb.MarshalOptions does.
 */
final class MarshalDepthTest {

    public static class Node {
        @ProtoField(1) Node child;
        @ProtoField(2) Map<String, Node> kids = new HashMap<>();
        public Node() {}
    }

    private static Node chain(int n) {
        Node root = new Node();
        Node at = root;
        for (int i = 0; i < n; i++) {
            at.child = new Node();
            at = at.child;
        }
        return root;
    }

    private static Node kids(int n) {
        Node root = new Node();
        Node at = root;
        for (int i = 0; i < n; i++) {
            Node next = new Node();
            at.kids.put("k", next);
            at = next;
        }
        return root;
    }

    private static int depthOf(Node n) {
        int d = 0;
        while (n.child != null) { n = n.child; d++; }
        return d;
    }

    private static void assertRefused(Node n, MarshalOptions opts) {
        IOException e = assertThrows(IOException.class, () -> Pb.marshal(n, opts));
        assertTrue(e.getMessage().contains("nesting depth exceeds MaxNestingDepth=" + opts.maxNestingDepth()), e.getMessage());
    }

    @Test
    void submessagesUpToTheLimitAreWritten() throws IOException {
        assertEquals(100, depthOf(Pb.unmarshal(Pb.marshal(chain(100)), Node.class)));
        assertRefused(chain(101), MarshalOptions.defaults());
    }

    @Test
    void aMapEntryIsADescent() throws IOException {
        // 50 entries, each holding a message: 100 descents.
        Pb.unmarshal(Pb.marshal(kids(50)), Node.class);
        Node past = kids(50);
        Node at = past;
        while (!at.kids.isEmpty()) at = at.kids.get("k");
        at.child = new Node();
        assertRefused(past, MarshalOptions.defaults());
    }

    @Test
    void aCycleMeetsTheLimitInsteadOfTheStack() {
        Node loop = new Node();
        loop.child = loop;
        assertRefused(loop, MarshalOptions.defaults());
    }

    @Test
    void theLimitIsPerCall() throws IOException {
        Pb.marshal(chain(150), MarshalOptions.defaults().withMaxNestingDepth(200));
        assertRefused(chain(11), MarshalOptions.defaults().withMaxNestingDepth(10));
        assertThrows(IllegalArgumentException.class, () -> new MarshalOptions(0));
    }
}
