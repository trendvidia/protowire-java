// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/** A lexical token. */
public record Token(TokenKind kind, String value, Position pos, String prec) {
    public Token(TokenKind kind, Position pos) { this(kind, "", pos, ""); }
    public Token(TokenKind kind, String value, Position pos) { this(kind, value, pos, ""); }
}
