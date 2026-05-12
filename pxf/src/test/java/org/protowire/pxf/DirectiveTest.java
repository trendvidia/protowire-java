// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser-side tests for the v0.72 named-directive form and the v0.73
 * {@code @entry} prefix-list generalization (draft §3.4.2, §3.4.3).
 */
class DirectiveTest {

    // --- v0.72: named directives ---

    @Test
    void namedDirectiveWithTypeAndBody() {
        var doc = Parser.parse("""
                @header chameleon.v1.LayerHeader {
                  id = "base"
                  encrypted = false
                }
                string_field = "x"
                """);
        assertEquals(1, doc.directives().size());
        var d = doc.directives().get(0);
        assertEquals("header", d.name());
        assertEquals(List.of("chameleon.v1.LayerHeader"), d.prefixes());
        assertEquals("chameleon.v1.LayerHeader", d.type()); // legacy back-compat
        assertNotNull(d.body());
        String body = new String(d.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("id = \"base\""));
        assertTrue(body.contains("encrypted = false"));
    }

    @Test
    void namedDirectiveNoBlock() {
        var doc = Parser.parse("@foo SomeType\nstring_field = \"x\"");
        assertEquals(1, doc.directives().size());
        var d = doc.directives().get(0);
        assertEquals("foo", d.name());
        assertEquals(List.of("SomeType"), d.prefixes());
        assertNull(d.body());
        assertEquals(1, doc.entries().size());
    }

    @Test
    void namedDirectiveNoType() {
        var doc = Parser.parse("@bare { id = \"x\" }\nstring_field = \"y\"");
        var d = doc.directives().get(0);
        assertEquals("bare", d.name());
        assertEquals(List.of(), d.prefixes());
        assertEquals("", d.type());
        assertNotNull(d.body());
    }

    @Test
    void multipleDirectivesOrderPreserved() {
        var doc = Parser.parse("""
                @header H { id = "x" }
                @trace T { sample = "0.1" }
                string_field = "y"
                """);
        assertEquals(2, doc.directives().size());
        assertEquals("header", doc.directives().get(0).name());
        assertEquals("trace", doc.directives().get(1).name());
    }

    @Test
    void atTypeBeforeDirectiveStillWorks() {
        var doc = Parser.parse("""
                @type test.v1.AllTypes
                @header H { id = "x" }
                string_field = "y"
                """);
        assertEquals("test.v1.AllTypes", doc.typeUrl());
        assertEquals(1, doc.directives().size());
        assertEquals("header", doc.directives().get(0).name());
    }

    // --- v0.73: zero-or-more prefix list ---

    @Test
    void twoPrefixesAtEntry() {
        var doc = Parser.parse("""
                @entry alice users.v1.User { name = "Alice" }
                string_field = "body"
                """);
        var d = doc.directives().get(0);
        assertEquals("entry", d.name());
        assertEquals(List.of("alice", "users.v1.User"), d.prefixes());
        // Two prefixes ⇒ legacy `type` field empty.
        assertEquals("", d.type());
    }

    @Test
    void threePrefixesAccepted() {
        var doc = Parser.parse("""
                @x a b c { id = "i" }
                string_field = "body"
                """);
        assertEquals(List.of("a", "b", "c"), doc.directives().get(0).prefixes());
        assertEquals("", doc.directives().get(0).type());
    }

    @Test
    void zeroPrefixesAnonymousEntry() {
        var doc = Parser.parse("""
                @entry { note = "metadata" }
                string_field = "body"
                """);
        var d = doc.directives().get(0);
        assertEquals(List.of(), d.prefixes());
        assertEquals("", d.type());
        assertNotNull(d.body());
    }

    @Test
    void prefixLookaheadDoesNotEatBodyFieldKey() {
        // Regression guard: a greedy IDENT loop swallows the first body
        // entry's key (`string_field`). One-token lookahead through `=`
        // prevents that.
        for (String in : new String[] {
                "@foo SomeType\nstring_field = \"x\"",
                "@foo\nstring_field = \"x\"",
                "@foo a b\nstring_field = \"x\""
        }) {
            var doc = Parser.parse(in);
            assertEquals(1, doc.directives().size(), in);
            assertEquals(1, doc.entries().size(), in);
        }
    }

    @Test
    void emptyDirectiveRejected() {
        // `@` followed by a non-identifier byte is ILLEGAL — should fail.
        assertThrows(PxfException.class, () -> Parser.parse("@\nstring_field = \"x\""));
    }
}
