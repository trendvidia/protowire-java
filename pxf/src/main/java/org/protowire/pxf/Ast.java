// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * AST node types for PXF documents. Sealed hierarchies of records, navigated via pattern matching.
 */
public final class Ast {
    private Ast() {}

    public record Comment(Position pos, String text) {}

    /** Root node of a PXF document. */
    public record Document(
            String typeUrl,
            List<Entry> entries,
            List<Comment> leadingComments) {

        public static Document of(String typeUrl, List<Entry> entries) {
            return new Document(typeUrl, List.copyOf(entries), List.of());
        }
    }

    /** A single entry inside a message body: assignment, map entry, or block. */
    public sealed interface Entry permits Assignment, MapEntry, Block {
        Position pos();
        List<Comment> leadingComments();
        String trailingComment();
    }

    public record Assignment(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    public record MapEntry(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    public record Block(
            Position pos,
            String name,
            List<Entry> entries,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    /** Right-hand-side value. */
    public sealed interface Value permits StringVal, IntVal, FloatVal, BoolVal, BytesVal, NullVal,
            IdentVal, TimestampVal, DurationVal, ListVal, BlockVal {
        Position pos();
    }

    public record StringVal(Position pos, String value) implements Value {}
    public record IntVal(Position pos, String raw) implements Value {}
    public record FloatVal(Position pos, String raw) implements Value {}
    public record BoolVal(Position pos, boolean value) implements Value {}
    public record BytesVal(Position pos, byte[] value) implements Value {}
    public record NullVal(Position pos) implements Value {}
    public record IdentVal(Position pos, String name) implements Value {}
    public record TimestampVal(Position pos, Instant value, String raw) implements Value {}
    public record DurationVal(Position pos, Duration value, String raw) implements Value {}
    public record ListVal(Position pos, List<Value> elements) implements Value {}
    public record BlockVal(Position pos, List<Entry> entries) implements Value {}
}
