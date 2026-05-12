// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * PXF schema-level conformance check per draft-trendvidia-protowire-00 §3.13.
 * A protobuf schema bound for PXF use MUST NOT declare a message field, oneof,
 * or enum value whose name is case-sensitively equal to a PXF value keyword
 * ({@code null} / {@code true} / {@code false}). Such names lex as PXF
 * keywords, so the declared element is unreachable from PXF surface syntax
 * — the binding silently can't be selected.
 *
 * <p>Enforcement runs at descriptor-bind time inside
 * {@link UnmarshalOptions#unmarshal(byte[], com.google.protobuf.Message.Builder)} /
 * {@link UnmarshalOptions#unmarshalFull(byte[], com.google.protobuf.Message.Builder)}.
 * Callers that have already validated their descriptors (codegen pre-screening,
 * registry-load passes) can set {@link UnmarshalOptions#skipValidate()} to
 * bypass the per-call recheck.
 *
 * <p>The check is case-sensitive: identifiers such as {@code "NULL"} or
 * {@code "True"} lex as ordinary identifiers and are accepted.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /**
     * Reserved-name set per draft §3.13. Case-sensitive — {@code NULL},
     * {@code True}, {@code FALSE} lex as ordinary identifiers and are
     * accepted.
     */
    static final Set<String> RESERVED_NAMES = Set.of("null", "true", "false");

    /** Which kind of schema element a {@link Violation} refers to. */
    public enum Kind {
        FIELD("message field"),
        ONEOF("oneof"),
        ENUM_VALUE("enum value");

        private final String label;

        Kind(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    /**
     * One schema element whose name collides with a reserved PXF keyword.
     *
     * @param file    {@code .proto} file path the offending element is in
     * @param element fully-qualified protobuf name (e.g.
     *                {@code "trades.v1.Side.null"})
     * @param name    the bare reserved identifier ({@code "null"} /
     *                {@code "true"} / {@code "false"})
     * @param kind    kind of element that collided
     */
    public record Violation(String file, String element, String name, Kind kind) {
        @Override public String toString() {
            return file + ": " + kind + " \"" + element + "\" uses PXF-reserved name \""
                    + name + "\" (draft §3.13)";
        }
    }

    /**
     * Walk the file containing {@code desc} and return every reserved-name
     * collision among messages, oneofs, and enum values reachable from
     * that file. Returned list is sorted by element fully-qualified name
     * for stable output. Empty list means the schema is conformant.
     */
    public static List<Violation> validateDescriptor(Descriptor desc) {
        if (desc == null) return List.of();
        return validateFile(desc.getFile());
    }

    /** Walk {@code fd} and return every reserved-name collision. */
    public static List<Violation> validateFile(FileDescriptor fd) {
        if (fd == null) return List.of();
        List<Violation> out = new ArrayList<>();
        for (Descriptor m : fd.getMessageTypes()) walkMessage(fd.getName(), m, out);
        for (EnumDescriptor e : fd.getEnumTypes()) walkEnum(fd.getName(), e, out);
        out.sort(Comparator.comparing(Violation::element));
        return List.copyOf(out);
    }

    private static void walkMessage(String file, Descriptor m, List<Violation> out) {
        for (FieldDescriptor f : m.getFields()) {
            String name = f.getName();
            if (RESERVED_NAMES.contains(name)) {
                out.add(new Violation(file, f.getFullName(), name, Kind.FIELD));
            }
        }
        for (OneofDescriptor o : m.getOneofs()) {
            // Skip synthetic oneofs introduced for proto3 optional fields:
            // their name is `_<fieldname>`, never in the reserved set, and
            // they'd double-count an already-reported field violation.
            if (o.isSynthetic()) continue;
            String name = o.getName();
            if (RESERVED_NAMES.contains(name)) {
                out.add(new Violation(file, o.getFullName(), name, Kind.ONEOF));
            }
        }
        for (Descriptor nested : m.getNestedTypes()) walkMessage(file, nested, out);
        for (EnumDescriptor nested : m.getEnumTypes()) walkEnum(file, nested, out);
    }

    private static void walkEnum(String file, EnumDescriptor e, List<Violation> out) {
        for (EnumValueDescriptor v : e.getValues()) {
            String name = v.getName();
            if (RESERVED_NAMES.contains(name)) {
                out.add(new Violation(file, v.getFullName(), name, Kind.ENUM_VALUE));
            }
        }
    }

    /**
     * Join a list of violations into a single human-readable error message.
     * Returns {@code null} when the list is empty. Used by the decoder to
     * surface validation failures via {@link PxfException}.
     */
    static String asMessage(List<Violation> violations) {
        if (violations.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("PXF schema reserved-name violations:");
        for (Violation v : violations) {
            sb.append("\n  ").append(v);
        }
        return sb.toString();
    }
}
