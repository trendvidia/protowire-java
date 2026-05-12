// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Fused single-pass decoder. Mirrors Go's {@code decode_fast.go}: lexes and writes directly into
 * a {@link Message.Builder} without allocating an AST.
 */
final class FastDecoder {
    private final Lexer lex;
    private Token current;
    private final UnmarshalOptions opts;
    private final boolean trackPresence;
    private Result result;
    private Message.Builder rootBuilder;
    private FieldDescriptor nullMaskFd;
    private String pathPrefix = "";

    FastDecoder(byte[] data, UnmarshalOptions opts, boolean trackPresence) {
        this.lex = new Lexer(data);
        this.opts = opts;
        this.trackPresence = trackPresence;
        if (trackPresence) this.result = new Result();
        advance();
    }

    Result result() { return result; }

    void decode(Message.Builder b) {
        this.rootBuilder = b;
        this.nullMaskFd = trackPresence ? WellKnown.findNullMaskField(b.getDescriptorForType()) : null;

        // Schema reserved-name check (draft §3.13). Runs by default — the
        // check catches schemas declaring fields/oneofs/enum values named
        // `null`/`true`/`false`, which would be silently unreachable from
        // PXF surface syntax. Callers that have already validated their
        // descriptors can set UnmarshalOptions.skipValidate to opt out.
        if (!opts.skipValidate()) {
            java.util.List<SchemaValidator.Violation> violations =
                    SchemaValidator.validateDescriptor(b.getDescriptorForType());
            String validationMsg = SchemaValidator.asMessage(violations);
            if (validationMsg != null) {
                throw new PxfException(Position.UNKNOWN, validationMsg);
            }
        }

        // Drain leading directives. @type populates the type binding;
        // @<name> and @table are side-channel — for the v0.72/v0.73
        // parser-side port we consume + discard so the body decode path
        // still works on documents that carry them. Full directive
        // recording on Result + per-table accessor land in a follow-up.
        boolean sawTable = false;
        Position firstTablePos = null;
        boolean sawType = false;
        directives:
        while (true) {
            switch (current.kind()) {
                case AT_TYPE -> {
                    if (sawTable) {
                        throw new PxfException(current.pos(),
                                "@table directive cannot coexist with @type (draft §3.4.4)");
                    }
                    sawType = true;
                    advance();
                    if (current.kind() != TokenKind.IDENT) {
                        throw new PxfException(current.pos(),
                                "expected type name after @type, got " + current.kind());
                    }
                    advance();
                }
                case AT_DIRECTIVE -> {
                    Ast.Directive dir = consumeNamedDirective();
                    if (trackPresence) result.addDirective(dir);
                }
                case AT_TABLE -> {
                    if (sawType) {
                        throw new PxfException(current.pos(),
                                "@table directive cannot coexist with @type (draft §3.4.4)");
                    }
                    if (firstTablePos == null) firstTablePos = current.pos();
                    sawTable = true;
                    Ast.TableDirective tbl = consumeTableDirective();
                    if (trackPresence) result.addTable(tbl);
                }
                default -> { break directives; }
            }
        }
        if (sawTable && current.kind() != TokenKind.EOF) {
            throw new PxfException(firstTablePos,
                    "@table directive cannot coexist with top-level field entries (draft §3.4.4)");
        }
        decodeFields(b, false);
        if (trackPresence) postDecode(b, "");
    }

    /**
     * Consume a {@code @<name> *(<prefix-id>) [{ ... }]} directive and
     * return an {@link Ast.Directive} record. AT_DIRECTIVE is current on
     * entry; on return, {@link #current} points at the next significant
     * token after the directive.
     */
    private Ast.Directive consumeNamedDirective() {
        Position pp = current.pos();
        String name = current.value();
        advance(); // consume @<name>

        // Zero or more prefix identifiers, with one-token lookahead so an
        // IDENT followed by `=` or `:` (i.e. a body field key) doesn't get
        // eaten as a directive prefix.
        java.util.List<String> prefixes = new java.util.ArrayList<>();
        while (current.kind() == TokenKind.IDENT) {
            Lexer.Mark mark = lex.mark();
            Token saved = current;
            advance();
            TokenKind nextKind = current.kind();
            if (nextKind == TokenKind.EQUALS || nextKind == TokenKind.COLON) {
                // The peeked IDENT is the first body field key, not a
                // prefix — rewind one token.
                lex.restore(mark);
                current = saved;
                break;
            }
            prefixes.add(saved.value());
        }
        // Back-compat: a single prefix populates the legacy `type` field.
        String type = prefixes.size() == 1 ? prefixes.get(0) : "";

        byte[] body = null;
        if (current.kind() == TokenKind.LBRACE) {
            int open = current.pos().offset();
            int close = lex.findMatchingBrace(open);
            if (close < 0) {
                throw new PxfException(current.pos(), "unmatched '{' in directive block");
            }
            body = lex.sliceBytes(open + 1, close);
            // Re-seat the lexer just past the closing `}`. Recompute
            // line/col by scanning so post-block tokens report their
            // true position (the old approach carried the pre-block
            // line/col, which was a pre-existing bug).
            int[] lc = lex.lineColAt(close + 1);
            lex.restore(new Lexer.Mark(close + 1, lc[0], lc[1]));
            advance();
        }
        return new Ast.Directive(pp, name,
                java.util.List.copyOf(prefixes), type, body, java.util.List.of());
    }

    /**
     * Consume a {@code @table <type> ( cols ) ( vals )...} directive and
     * return an {@link Ast.TableDirective} record. AT_TABLE is current on
     * entry. The same parser-tier enforcement applies: row arity, dotted-
     * column rejection, list/block-cell rejection.
     */
    private Ast.TableDirective consumeTableDirective() {
        Position pp = current.pos();
        advance(); // consume @table

        if (current.kind() != TokenKind.IDENT) {
            throw new PxfException(current.pos(),
                    "expected row message type after @table, got " + current.kind());
        }
        String type = current.value();
        advance();
        if (current.kind() != TokenKind.LPAREN) {
            throw new PxfException(current.pos(),
                    "expected '(' to start @table column list, got " + current.kind());
        }
        advance();
        if (current.kind() != TokenKind.IDENT) {
            throw new PxfException(current.pos(),
                    "@table column list must contain at least one field name, got " + current.kind());
        }
        java.util.List<String> columns = new java.util.ArrayList<>();
        while (true) {
            if (current.kind() != TokenKind.IDENT) {
                throw new PxfException(current.pos(),
                        "expected column field name, got " + current.kind());
            }
            String colName = current.value();
            if (colName.indexOf('.') >= 0) {
                throw new PxfException(current.pos(),
                        "@table column \"" + colName + "\": dotted column paths are not supported in v1 (draft §3.4.4)");
            }
            columns.add(colName);
            advance();
            if (current.kind() == TokenKind.COMMA) { advance(); continue; }
            if (current.kind() == TokenKind.RPAREN) break;
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @table column list, got " + current.kind());
        }
        advance(); // consume )

        java.util.List<Ast.TableRow> rows = new java.util.ArrayList<>();
        while (current.kind() == TokenKind.LPAREN) {
            rows.add(consumeTableRow(columns.size()));
        }
        return new Ast.TableDirective(pp, type,
                java.util.List.copyOf(columns),
                java.util.List.copyOf(rows),
                java.util.List.of());
    }

    private Ast.TableRow consumeTableRow(int expected) {
        Position pp = current.pos();
        advance(); // consume (

        java.util.List<Ast.Value> cells = new java.util.ArrayList<>();
        cells.add(consumeRowCell());
        while (current.kind() == TokenKind.COMMA) {
            advance();
            cells.add(consumeRowCell());
        }
        if (current.kind() != TokenKind.RPAREN) {
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @table row, got " + current.kind());
        }
        advance();
        if (cells.size() != expected) {
            throw new PxfException(pp,
                    "@table row has " + cells.size() + " cells, expected " + expected + " (column count)");
        }
        // Cells legitimately contain null entries; List.copyOf rejects nulls.
        return new Ast.TableRow(pp,
                java.util.Collections.unmodifiableList(new java.util.ArrayList<>(cells)));
    }

    /**
     * Consume one cell of a @table row. Returns {@code null} for an empty
     * cell (no value between commas, or at row start/end). Rejects list
     * and block values per v1 cell-grammar (draft §3.4.4).
     */
    private Ast.Value consumeRowCell() {
        switch (current.kind()) {
            case COMMA, RPAREN -> { return null; }
            case LBRACKET -> throw new PxfException(current.pos(),
                    "@table cells cannot contain list values in v1 (draft §3.4.4)");
            case LBRACE -> throw new PxfException(current.pos(),
                    "@table cells cannot contain block values in v1 (draft §3.4.4)");
            default -> { /* fall through to value consumer */ }
        }
        return consumeAstValue();
    }

    /**
     * Consume one PXF leaf value from the current token stream and
     * return the matching {@link Ast.Value} record. Mirrors
     * {@link Parser#parseValue()} for the subset of values that can
     * appear in a @table cell (list and block values are rejected at
     * {@link #consumeRowCell} before reaching here).
     *
     * <p>Duplicates the switch arms in Parser.parseValue. The
     * alternative — extracting a static helper from Parser — would
     * require threading lexer + token state through the call, which is
     * more code for no measurable win. Same trade the Go port made for
     * {@code decode_fast.consumeValue}.
     */
    private Ast.Value consumeAstValue() {
        Position pp = current.pos();
        switch (current.kind()) {
            case STRING -> {
                var v = new Ast.StringVal(pp, current.value());
                advance();
                return v;
            }
            case INT -> {
                var v = new Ast.IntVal(pp, current.value());
                advance();
                return v;
            }
            case FLOAT -> {
                var v = new Ast.FloatVal(pp, current.value());
                advance();
                return v;
            }
            case BOOL -> {
                var v = new Ast.BoolVal(pp, "true".equals(current.value()));
                advance();
                return v;
            }
            case BYTES -> {
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(current.value()); }
                catch (IllegalArgumentException e) {
                    decoded = Base64.getDecoder().decode(current.value() + Lexer.padding(current.value()));
                }
                var v = new Ast.BytesVal(pp, decoded);
                advance();
                return v;
            }
            case TIMESTAMP -> {
                var v = new Ast.TimestampVal(pp, WellKnown.parseRfc3339(current.value()), current.value());
                advance();
                return v;
            }
            case DURATION -> {
                var v = new Ast.DurationVal(pp, WellKnown.parseGoDuration(current.value()), current.value());
                advance();
                return v;
            }
            case NULL -> {
                var v = new Ast.NullVal(pp);
                advance();
                return v;
            }
            case IDENT -> {
                var v = new Ast.IdentVal(pp, current.value());
                advance();
                return v;
            }
            default -> throw new PxfException(pp,
                    "expected value, got " + current.kind() + " (\"" + current.value() + "\")");
        }
    }

    private void advance() {
        while (true) {
            current = lex.next();
            if (current.kind() == TokenKind.NEWLINE) continue;
            if (current.kind() == TokenKind.COMMENT) continue;
            return;
        }
    }

    // -- core --------------------------------------------------------------

    private void decodeFields(Message.Builder b, boolean inBlock) {
        Descriptor desc = b.getDescriptorForType();
        Map<String, String> setOneofs = null;

        while (true) {
            if (inBlock && current.kind() == TokenKind.RBRACE) { advance(); return; }
            if (current.kind() == TokenKind.EOF) {
                if (inBlock) throw new PxfException(current.pos(), "expected '}', got EOF");
                return;
            }
            Position pos = current.pos();
            if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING && current.kind() != TokenKind.INT) {
                throw new PxfException(pos, "expected identifier, string, or integer, got " + current.kind() + " (\"" + current.value() + "\")");
            }
            String key = current.value();
            advance();

            switch (current.kind()) {
                case EQUALS -> {
                    advance();
                    FieldDescriptor fd = desc.findFieldByName(key);
                    if (fd == null) {
                        if (opts.discardUnknown()) { skipValue(); continue; }
                        throw new PxfException(pos, "unknown field \"" + key + "\" in " + desc.getFullName());
                    }
                    setOneofs = checkOneof(fd, setOneofs, pos);
                    if (current.kind() == TokenKind.NULL) {
                        if (trackPresence) {
                            String path = pathPrefix + fd.getName();
                            result.markNull(path);
                            if (nullMaskFd != null) addToNullMask(path);
                        }
                        advance();
                        continue;
                    }
                    if (trackPresence) result.markPresent(pathPrefix + fd.getName());
                    decodeFieldValue(b, fd);
                }
                case LBRACE -> {
                    advance();
                    FieldDescriptor fd = desc.findFieldByName(key);
                    if (fd == null) {
                        if (opts.discardUnknown()) { skipBraced(); continue; }
                        throw new PxfException(pos, "unknown field \"" + key + "\" in " + desc.getFullName());
                    }
                    if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                        throw new PxfException(pos, "field \"" + key + "\" is not a message type, cannot use block syntax");
                    }
                    if (fd.isRepeated() && !fd.isMapField()) {
                        throw new PxfException(pos, "repeated field \"" + key + "\" must use list syntax: " + key + " = [...]");
                    }
                    if (fd.isMapField()) {
                        throw new PxfException(pos, "map field \"" + key + "\" must use assignment syntax: " + key + " = { ... }");
                    }
                    setOneofs = checkOneof(fd, setOneofs, pos);
                    if (trackPresence) result.markPresent(pathPrefix + fd.getName());

                    if (WellKnown.isAny(fd.getMessageType()) && opts.typeResolver() != null && current.kind() == TokenKind.AT_TYPE) {
                        decodeAnyInner(b, fd);
                        continue;
                    }
                    Message.Builder sub = b.newBuilderForField(fd);
                    String saved = pathPrefix;
                    pathPrefix = pathPrefix + fd.getName() + ".";
                    decodeFields(sub, true);
                    pathPrefix = saved;
                    b.setField(fd, sub.build());
                }
                case COLON -> throw new PxfException(pos, "unexpected ':' in message context, use '=' for field assignments");
                default -> throw new PxfException(current.pos(), "expected '=', ':', or '{' after \"" + key + "\", got " + current.kind());
            }
        }
    }

    private Map<String, String> checkOneof(FieldDescriptor fd, Map<String, String> setOneofs, Position pos) {
        OneofDescriptor oo = fd.getContainingOneof();
        if (oo == null || oo.isSynthetic()) return setOneofs;
        if (setOneofs == null) setOneofs = new HashMap<>();
        String prev = setOneofs.get(oo.getName());
        if (prev != null) throw new PxfException(pos, "oneof \"" + oo.getName() + "\": field \"" + fd.getName() + "\" conflicts with already-set field \"" + prev + "\"");
        setOneofs.put(oo.getName(), fd.getName());
        return setOneofs;
    }

    private void decodeFieldValue(Message.Builder b, FieldDescriptor fd) {
        if (fd.isMapField())  { decodeMap(b, fd); return; }
        if (fd.isRepeated())  { decodeList(b, fd); return; }
        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) { decodeMsgValue(b, fd); return; }
        b.setField(fd, consumeScalar(fd));
    }

    private void decodeMsgValue(Message.Builder b, FieldDescriptor fd) {
        Descriptor md = fd.getMessageType();

        if (WellKnown.isTimestamp(md) && current.kind() == TokenKind.TIMESTAMP) {
            Instant t = WellKnown.parseRfc3339(current.value());
            Message.Builder sub = b.newBuilderForField(fd);
            WellKnown.setTimestamp(sub, t);
            b.setField(fd, sub.build());
            advance();
            return;
        }
        if (WellKnown.isDuration(md) && current.kind() == TokenKind.DURATION) {
            Duration d = WellKnown.parseGoDuration(current.value());
            Message.Builder sub = b.newBuilderForField(fd);
            WellKnown.setDuration(sub, d);
            b.setField(fd, sub.build());
            advance();
            return;
        }
        if (WellKnown.isWrapper(md) && current.kind() != TokenKind.LBRACE) {
            FieldDescriptor inner = md.findFieldByName("value");
            Message.Builder sub = b.newBuilderForField(fd);
            sub.setField(inner, consumeScalar(inner));
            b.setField(fd, sub.build());
            return;
        }
        if (WellKnown.isBigInt(md) && current.kind() == TokenKind.INT) {
            Message.Builder sub = b.newBuilderForField(fd);
            WellKnown.setBigInt(sub, WellKnown.parseBigInt(current.value()));
            b.setField(fd, sub.build());
            advance();
            return;
        }
        if (WellKnown.isDecimal(md) && (current.kind() == TokenKind.INT || current.kind() == TokenKind.FLOAT)) {
            Object[] parts = WellKnown.parseDecimal(current.value());
            Message.Builder sub = b.newBuilderForField(fd);
            // unscaled is BigInteger absolute value
            java.math.BigInteger unscaled = (java.math.BigInteger) parts[0];
            int scale = (Integer) parts[1];
            boolean neg = (Boolean) parts[2];
            // build a BigDecimal: applies sign to unscaled
            java.math.BigDecimal bd = new java.math.BigDecimal(neg ? unscaled.negate() : unscaled, scale);
            WellKnown.setDecimal(sub, bd);
            b.setField(fd, sub.build());
            advance();
            return;
        }
        if (WellKnown.isBigFloat(md) && (current.kind() == TokenKind.INT || current.kind() == TokenKind.FLOAT)) {
            Message.Builder sub = b.newBuilderForField(fd);
            WellKnown.setBigFloat(sub, WellKnown.parseBigFloat(current.value()));
            b.setField(fd, sub.build());
            advance();
            return;
        }
        if (WellKnown.isAny(md) && opts.typeResolver() != null && current.kind() == TokenKind.LBRACE) {
            advance(); // consume {
            decodeAnyInner(b, fd);
            return;
        }
        if (current.kind() != TokenKind.LBRACE) {
            throw new PxfException(current.pos(), "expected '{' for message field \"" + fd.getName() + "\"");
        }
        advance();
        Message.Builder sub = b.newBuilderForField(fd);
        decodeFields(sub, true);
        b.setField(fd, sub.build());
    }

    private void decodeAnyInner(Message.Builder b, FieldDescriptor fd) {
        if (current.kind() != TokenKind.AT_TYPE) {
            throw new PxfException(current.pos(), "Any field requires @type as first entry");
        }
        advance();
        if (current.kind() != TokenKind.EQUALS) {
            throw new PxfException(current.pos(), "expected '=' after @type");
        }
        advance();
        if (current.kind() != TokenKind.STRING) {
            throw new PxfException(current.pos(), "expected string type URL after @type =");
        }
        String typeUrl = current.value();
        advance();

        Descriptor innerDesc = opts.typeResolver().findMessageByUrl(typeUrl);
        if (innerDesc == null) throw new PxfException(current.pos(), "cannot resolve Any type \"" + typeUrl + "\"");

        DynamicMessage.Builder inner = DynamicMessage.newBuilder(innerDesc);
        decodeFields(inner, true);
        DynamicMessage built = inner.build();

        Message.Builder anyB = b.newBuilderForField(fd);
        Descriptor anyDesc = fd.getMessageType();
        anyB.setField(anyDesc.findFieldByName("type_url"), typeUrl);
        anyB.setField(anyDesc.findFieldByName("value"), built.toByteString());
        b.setField(fd, anyB.build());
    }

    private void decodeList(Message.Builder b, FieldDescriptor fd) {
        if (current.kind() != TokenKind.LBRACKET) {
            throw new PxfException(current.pos(), "expected '[' for repeated field \"" + fd.getName() + "\"");
        }
        advance();
        while (current.kind() != TokenKind.RBRACKET && current.kind() != TokenKind.EOF) {
            if (current.kind() == TokenKind.NULL) {
                throw new PxfException(current.pos(), "null is not allowed in repeated field \"" + fd.getName() + "\"");
            }
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                Object elem = consumeListMsg(b, fd);
                b.addRepeatedField(fd, elem);
            } else if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                b.addRepeatedField(fd, consumeEnum(fd));
            } else {
                b.addRepeatedField(fd, consumeScalar(fd));
            }
            if (current.kind() == TokenKind.COMMA) advance();
        }
        if (current.kind() != TokenKind.RBRACKET) {
            throw new PxfException(current.pos(), "expected ']', got " + current.kind());
        }
        advance();
    }

    private Object consumeListMsg(Message.Builder parent, FieldDescriptor fd) {
        Descriptor md = fd.getMessageType();
        if (WellKnown.isTimestamp(md) && current.kind() == TokenKind.TIMESTAMP) {
            Instant t = WellKnown.parseRfc3339(current.value());
            Message.Builder sub = parent.newBuilderForField(fd);
            WellKnown.setTimestamp(sub, t);
            advance();
            return sub.build();
        }
        if (WellKnown.isDuration(md) && current.kind() == TokenKind.DURATION) {
            Duration d = WellKnown.parseGoDuration(current.value());
            Message.Builder sub = parent.newBuilderForField(fd);
            WellKnown.setDuration(sub, d);
            advance();
            return sub.build();
        }
        if (WellKnown.isWrapper(md) && current.kind() != TokenKind.LBRACE) {
            FieldDescriptor inner = md.findFieldByName("value");
            Message.Builder sub = parent.newBuilderForField(fd);
            sub.setField(inner, consumeScalar(inner));
            return sub.build();
        }
        if (WellKnown.isBigInt(md) && current.kind() == TokenKind.INT) {
            Message.Builder sub = parent.newBuilderForField(fd);
            WellKnown.setBigInt(sub, WellKnown.parseBigInt(current.value()));
            advance();
            return sub.build();
        }
        if (WellKnown.isDecimal(md) && (current.kind() == TokenKind.INT || current.kind() == TokenKind.FLOAT)) {
            Object[] parts = WellKnown.parseDecimal(current.value());
            java.math.BigInteger unscaled = (java.math.BigInteger) parts[0];
            int scale = (Integer) parts[1];
            boolean neg = (Boolean) parts[2];
            java.math.BigDecimal bd = new java.math.BigDecimal(neg ? unscaled.negate() : unscaled, scale);
            Message.Builder sub = parent.newBuilderForField(fd);
            WellKnown.setDecimal(sub, bd);
            advance();
            return sub.build();
        }
        if (WellKnown.isBigFloat(md) && (current.kind() == TokenKind.INT || current.kind() == TokenKind.FLOAT)) {
            Message.Builder sub = parent.newBuilderForField(fd);
            WellKnown.setBigFloat(sub, WellKnown.parseBigFloat(current.value()));
            advance();
            return sub.build();
        }
        if (current.kind() != TokenKind.LBRACE) {
            throw new PxfException(current.pos(), "expected '{' for repeated message element");
        }
        advance();
        Message.Builder sub = parent.newBuilderForField(fd);
        decodeFields(sub, true);
        return sub.build();
    }

    private void decodeMap(Message.Builder b, FieldDescriptor fd) {
        if (current.kind() != TokenKind.LBRACE) {
            throw new PxfException(current.pos(), "expected '{' for map field \"" + fd.getName() + "\"");
        }
        advance();
        Descriptor entryType = fd.getMessageType();
        FieldDescriptor keyFd = entryType.findFieldByName("key");
        FieldDescriptor valFd = entryType.findFieldByName("value");

        while (current.kind() != TokenKind.RBRACE && current.kind() != TokenKind.EOF) {
            Position pos = current.pos();
            if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING && current.kind() != TokenKind.INT) {
                throw new PxfException(pos, "expected map key, got " + current.kind());
            }
            String keyStr = current.value();
            advance();

            switch (current.kind()) {
                case COLON -> advance();
                case EQUALS -> throw new PxfException(current.pos(), "unexpected '=' in map, use ':' for map entries");
                default -> throw new PxfException(current.pos(), "expected ':' after map key, got " + current.kind());
            }

            if (current.kind() == TokenKind.NULL) {
                throw new PxfException(current.pos(), "null is not allowed as map value in field \"" + fd.getName() + "\"");
            }

            Message.Builder entry = b.newBuilderForField(fd);
            entry.setField(keyFd, decodeMapKey(keyFd, keyStr, pos));

            if (valFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (current.kind() != TokenKind.LBRACE) {
                    throw new PxfException(current.pos(), "expected '{' for map message value");
                }
                advance();
                Message.Builder valB = entry.newBuilderForField(valFd);
                decodeFields(valB, true);
                entry.setField(valFd, valB.build());
            } else if (valFd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                entry.setField(valFd, consumeEnum(valFd));
            } else {
                entry.setField(valFd, consumeScalar(valFd));
            }
            b.addRepeatedField(fd, entry.build());
        }
        if (current.kind() != TokenKind.RBRACE) {
            throw new PxfException(current.pos(), "expected '}', got " + current.kind());
        }
        advance();
    }

    private Object decodeMapKey(FieldDescriptor fd, String key, Position pos) {
        try {
            return switch (fd.getJavaType()) {
                case STRING -> key;
                case INT -> Integer.parseInt(key);
                case LONG -> Long.parseLong(key);
                case BOOLEAN -> Boolean.parseBoolean(key);
                default -> throw new PxfException(pos, "unsupported map key kind: " + fd.getJavaType());
            };
        } catch (NumberFormatException e) {
            throw new PxfException(pos, "invalid map key for kind " + fd.getJavaType() + ": " + key);
        }
    }

    private Object consumeScalar(FieldDescriptor fd) {
        Position pos = current.pos();
        switch (fd.getJavaType()) {
            case STRING:
                if (current.kind() != TokenKind.STRING) throw new PxfException(pos, "expected string for field \"" + fd.getName() + "\"");
                String s = current.value(); advance(); return s;
            case BOOLEAN:
                if (current.kind() != TokenKind.BOOL) throw new PxfException(pos, "expected bool for field \"" + fd.getName() + "\"");
                boolean bv = "true".equals(current.value()); advance(); return bv;
            case INT:
                if (current.kind() != TokenKind.INT) throw new PxfException(pos, "expected integer for field \"" + fd.getName() + "\"");
                int iv = (int) Long.parseLong(current.value()); advance(); return iv;
            case LONG:
                if (current.kind() != TokenKind.INT) throw new PxfException(pos, "expected integer for field \"" + fd.getName() + "\"");
                long lv = Long.parseLong(current.value()); advance(); return lv;
            case FLOAT:
                if (current.kind() != TokenKind.FLOAT && current.kind() != TokenKind.INT) throw new PxfException(pos, "expected number for field \"" + fd.getName() + "\"");
                float fv = Float.parseFloat(current.value()); advance(); return fv;
            case DOUBLE:
                if (current.kind() != TokenKind.FLOAT && current.kind() != TokenKind.INT) throw new PxfException(pos, "expected number for field \"" + fd.getName() + "\"");
                double dv = Double.parseDouble(current.value()); advance(); return dv;
            case BYTE_STRING:
                if (current.kind() != TokenKind.BYTES) throw new PxfException(pos, "expected bytes for field \"" + fd.getName() + "\"");
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(current.value()); }
                catch (IllegalArgumentException e) {
                    decoded = Base64.getDecoder().decode(current.value() + Lexer.padding(current.value()));
                }
                advance();
                return ByteString.copyFrom(decoded);
            case ENUM:
                return consumeEnum(fd);
            default:
                throw new PxfException(pos, "unsupported kind " + fd.getJavaType() + " for field \"" + fd.getName() + "\"");
        }
    }

    private EnumValueDescriptor consumeEnum(FieldDescriptor fd) {
        Position pos = current.pos();
        switch (current.kind()) {
            case IDENT -> {
                EnumValueDescriptor ev = fd.getEnumType().findValueByName(current.value());
                if (ev == null) throw new PxfException(pos, "unknown enum value \"" + current.value() + "\" for " + fd.getEnumType().getFullName());
                advance();
                return ev;
            }
            case INT -> {
                int n = Integer.parseInt(current.value());
                EnumValueDescriptor ev = fd.getEnumType().findValueByNumber(n);
                if (ev == null) ev = fd.getEnumType().findValueByNumberCreatingIfUnknown(n);
                advance();
                return ev;
            }
            default -> throw new PxfException(pos, "expected enum name or number for field \"" + fd.getName() + "\"");
        }
    }

    private void skipValue() {
        switch (current.kind()) {
            case LBRACE -> { advance(); skipBraced(); }
            case LBRACKET -> { advance(); skipBracketed(); }
            default -> advance();
        }
    }

    private void skipBraced() {
        int depth = 1;
        while (depth > 0 && current.kind() != TokenKind.EOF) {
            switch (current.kind()) {
                case LBRACE -> depth++;
                case RBRACE -> depth--;
                default -> {}
            }
            advance();
        }
    }

    private void skipBracketed() {
        int depth = 1;
        while (depth > 0 && current.kind() != TokenKind.EOF) {
            switch (current.kind()) {
                case LBRACKET -> depth++;
                case RBRACKET -> depth--;
                default -> {}
            }
            advance();
        }
    }

    private void addToNullMask(String path) {
        Message.Builder fmB = rootBuilder.newBuilderForField(nullMaskFd);
        // merge with existing if any
        if (rootBuilder.hasField(nullMaskFd)) {
            fmB.mergeFrom((Message) rootBuilder.getField(nullMaskFd));
        }
        FieldDescriptor pathsFd = fmB.getDescriptorForType().findFieldByName("paths");
        fmB.addRepeatedField(pathsFd, path);
        rootBuilder.setField(nullMaskFd, fmB.build());
    }

    private void postDecode(Message.Builder b, String pathPref) {
        Descriptor desc = b.getDescriptorForType();
        for (FieldDescriptor fd : desc.getFields()) {
            if (nullMaskFd != null && fd.getNumber() == nullMaskFd.getNumber()) continue;
            String path = pathPref + fd.getName();
            boolean present = result.presentFieldsView().contains(path);
            if (!present) {
                if (Annotations.isRequired(fd)) {
                    throw new PxfException(Position.UNKNOWN, "required field \"" + path + "\" is absent");
                }
                String def = Annotations.getDefault(fd);
                if (def != null) applyDefault(b, fd, def);
            } else if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                    && !fd.isRepeated() && !fd.isMapField()
                    && !result.isNull(path) && b.hasField(fd)) {
                Message.Builder sub = b.getFieldBuilder(fd);
                postDecode(sub, path + ".");
            }
        }
    }

    private void applyDefault(Message.Builder b, FieldDescriptor fd, String def) {
        switch (fd.getJavaType()) {
            case STRING  -> b.setField(fd, def);
            case BOOLEAN -> b.setField(fd, "true".equals(def));
            case INT     -> b.setField(fd, Integer.parseInt(def));
            case LONG    -> b.setField(fd, Long.parseLong(def));
            case FLOAT   -> b.setField(fd, Float.parseFloat(def));
            case DOUBLE  -> b.setField(fd, Double.parseDouble(def));
            case BYTE_STRING -> b.setField(fd, ByteString.copyFrom(Base64.getDecoder().decode(def)));
            case ENUM -> {
                EnumValueDescriptor ev = fd.getEnumType().findValueByName(def);
                if (ev == null) {
                    int n = Integer.parseInt(def);
                    ev = fd.getEnumType().findValueByNumberCreatingIfUnknown(n);
                }
                b.setField(fd, ev);
            }
            case MESSAGE -> applyMessageDefault(b, fd, def);
        }
    }

    private void applyMessageDefault(Message.Builder b, FieldDescriptor fd, String def) {
        Descriptor md = fd.getMessageType();
        Message.Builder sub = b.newBuilderForField(fd);
        if (WellKnown.isTimestamp(md)) {
            WellKnown.setTimestamp(sub, WellKnown.parseRfc3339(def));
        } else if (WellKnown.isDuration(md)) {
            WellKnown.setDuration(sub, WellKnown.parseGoDuration(def));
        } else if (WellKnown.isWrapper(md)) {
            FieldDescriptor inner = md.findFieldByName("value");
            sub.setField(inner, parseScalarDefault(inner, def));
        } else if (WellKnown.isBigInt(md)) {
            WellKnown.setBigInt(sub, WellKnown.parseBigInt(def));
        } else if (WellKnown.isDecimal(md)) {
            Object[] parts = WellKnown.parseDecimal(def);
            java.math.BigInteger unscaled = (java.math.BigInteger) parts[0];
            int scale = (Integer) parts[1];
            boolean neg = (Boolean) parts[2];
            WellKnown.setDecimal(sub, new java.math.BigDecimal(neg ? unscaled.negate() : unscaled, scale));
        } else if (WellKnown.isBigFloat(md)) {
            WellKnown.setBigFloat(sub, WellKnown.parseBigFloat(def));
        } else {
            throw new PxfException(Position.UNKNOWN,
                    "default values not supported for message type " + md.getFullName() + " (field \"" + fd.getName() + "\")");
        }
        b.setField(fd, sub.build());
    }

    private Object parseScalarDefault(FieldDescriptor fd, String def) {
        return switch (fd.getJavaType()) {
            case STRING  -> def;
            case BOOLEAN -> "true".equals(def);
            case INT     -> Integer.parseInt(def);
            case LONG    -> Long.parseLong(def);
            case FLOAT   -> Float.parseFloat(def);
            case DOUBLE  -> Double.parseDouble(def);
            case BYTE_STRING -> ByteString.copyFrom(Base64.getDecoder().decode(def));
            default -> throw new PxfException(Position.UNKNOWN, "unsupported default kind " + fd.getJavaType() + " for field \"" + fd.getName() + "\"");
        };
    }
}
