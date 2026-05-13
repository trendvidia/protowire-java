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
    LPAREN("("),    // @dataset column list and row tuples
    RPAREN(")"),
    EQUALS("="),
    COLON(":"),
    COMMA(","),

    AT_TYPE("@type"),
    AT_DATASET("@dataset"),            // row-oriented bulk-data directive (draft §3.4.4)
    AT_PROTO("@proto"),                // embedded protobuf schema (draft §3.4.5)
    AT_DIRECTIVE("@directive");        // @<name> for any non-reserved name

    private final String display;

    TokenKind(String display) { this.display = display; }

    @Override
    public String toString() { return display; }
}
