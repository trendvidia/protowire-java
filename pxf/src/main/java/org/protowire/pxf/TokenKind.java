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
    LPAREN("("),    // @table column list and row tuples
    RPAREN(")"),
    EQUALS("="),
    COLON(":"),
    COMMA(","),

    AT_TYPE("@type"),
    AT_TABLE("@table"),                // bulk-row directive (draft §3.4.4)
    AT_DIRECTIVE("@directive");        // @<name> for name != "type"/"table"

    private final String display;

    TokenKind(String display) { this.display = display; }

    @Override
    public String toString() { return display; }
}
