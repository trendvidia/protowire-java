// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

public enum TokenKind {
    EOF("EOF"),
    ILLEGAL("ILLEGAL"),
    NEWLINE("newline"),
    COMMENT("comment"),

    IDENT("identifier"),
    STRING("string"),
    INT("integer"),
    FLOAT("float"),
    BOOL("bool"),
    NULL("null"),
    BYTES("bytes"),
    TIMESTAMP("timestamp"),
    DURATION("duration"),

    LBRACE("{"),
    RBRACE("}"),
    LBRACKET("["),
    RBRACKET("]"),
    EQUALS("="),
    COLON(":"),
    COMMA(","),

    AT_TYPE("@type");

    private final String display;

    TokenKind(String display) { this.display = display; }

    @Override
    public String toString() { return display; }
}
