// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/**
 * A keyed-repeated-field error (draft -01 §3.13), as protowire-go's
 * {@code KeyedError} (#208). The decoder throws it, and
 * {@link Pxf#keyedDiagnostics} returns every one in a parsed document. Tell
 * the causes apart by {@link #kind()}.
 */
public final class KeyedException extends PxfException {

    /** What is wrong, as protowire-go's {@code KeyedErrorKind}. */
    public enum Kind {
        /** Two entries of one keyed block whose names are equal once unquoted. */
        DUPLICATE_KEY("duplicate key"),
        /**
         * A binding in a named entry that disagrees with its name: an
         * explicit key-field assignment of another value, {@code null} on the
         * key field, or another member of its oneof.
         */
        KEY_CONFLICT("key conflict"),
        /** The empty string as a key: a quoted entry name {@code ""}, or an explicit empty assignment to the key field. */
        EMPTY_KEY("empty key"),
        /** A quoted entry name outside a keyed repeated field's block, where a string never names a field. */
        QUOTED_NAME_UNKEYED("quoted entry name outside keyed field");

        private final String text;

        Kind(String text) { this.text = text; }

        /** As protowire-go's {@code KeyedErrorKind.String}. */
        @Override public String toString() { return text; }
    }

    private final Kind kind;
    private final String field;
    private final String key;
    private final String detail;

    private KeyedException(Position pos, Kind kind, String field, String key, String detail) {
        super(pos, detail);
        this.kind = kind;
        this.field = field;
        this.key = key;
        this.detail = detail;
    }

    public Kind kind() { return kind; }

    /** The keyed repeated field's PXF name; "" for {@link Kind#QUOTED_NAME_UNKEYED}, where there is none. */
    public String field() { return field; }

    /** The offending key or entry name, where one applies, or "". */
    public String key() { return key; }

    /** The message without its position. */
    public String detail() { return detail; }

    static KeyedException emptyName(Position pos, String field) {
        return new KeyedException(pos, Kind.EMPTY_KEY, field, "",
                "empty entry name in keyed field " + Token.quote(field) + ": the empty string is not a valid key");
    }

    static KeyedException duplicateKey(Position pos, String field, String key) {
        return new KeyedException(pos, Kind.DUPLICATE_KEY, field, key,
                "duplicate key " + Token.quote(key) + " in keyed field " + Token.quote(field));
    }

    static KeyedException quotedNameUnkeyed(Position pos, String name) {
        return new KeyedException(pos, Kind.QUOTED_NAME_UNKEYED, "", name,
                "quoted entry name " + Token.quote(name) + " is only valid inside a keyed repeated field's block (draft -01 §3.13)");
    }

    static KeyedException emptyExplicitKey(Position pos, String field, String keyName) {
        return new KeyedException(pos, Kind.EMPTY_KEY, field, "",
                "explicit empty-string assignment to key field " + Token.quote(keyName) + " of keyed field "
                        + Token.quote(field) + ": the empty string is not a valid key");
    }

    static KeyedException keyConflict(Position pos, String field, String keyName, String value, String entryName) {
        return new KeyedException(pos, Kind.KEY_CONFLICT, field, entryName,
                "key field " + Token.quote(keyName) + " = " + Token.quote(value) + " conflicts with entry name "
                        + Token.quote(entryName) + " in keyed field " + Token.quote(field));
    }

    static KeyedException nullOnKey(Position pos, String field, String keyName, String entryName) {
        return new KeyedException(pos, Kind.KEY_CONFLICT, field, entryName,
                "null on key field " + Token.quote(keyName) + " conflicts with entry name " + Token.quote(entryName)
                        + " in keyed field " + Token.quote(field));
    }

    static KeyedException oneofSibling(Position pos, String field, String fieldName, String oneof, String keyName, String entryName) {
        return new KeyedException(pos, Kind.KEY_CONFLICT, field, entryName,
                "field " + Token.quote(fieldName) + " shares oneof " + Token.quote(oneof) + " with key field " + Token.quote(keyName)
                        + ", which entry name " + Token.quote(entryName) + " already set in keyed field " + Token.quote(field));
    }
}
