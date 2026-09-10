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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PXF bind-time schema checks (draft -01 §schema-constraints and
 * §annotation-extensions; #54). A protobuf schema bound for PXF use
 * MUST NOT:
 *
 * <ul>
 *   <li>declare a message field, oneof, or enum value whose name is
 *       case-sensitively equal to a PXF value keyword ({@code null} /
 *       {@code true} / {@code false}) — such names lex as keywords, so
 *       the element is unreachable from PXF surface syntax (§3.14);</li>
 *   <li>carry {@code (pxf.key)} anywhere but on a repeated message-typed
 *       field, with a value naming a singular string field of the element
 *       message (§3.13.1 "Schema Placement");</li>
 *   <li>carry {@code (pxf.default)} where one PXF literal cannot denote
 *       the field — a {@code repeated} field, a {@code map<K,V>}, a group,
 *       or a message type outside Timestamp, Duration, the nine
 *       {@code *Value} wrappers, {@code pxf.BigInt}, {@code pxf.Decimal},
 *       {@code pxf.BigFloat} (§6.1.1 "Default Placement");</li>
 *   <li>carry {@code (pxf.default)} on more than one member of a oneof,
 *       or {@code (pxf.required)} on any member of one (§6.1.2 "Oneof
 *       Members").</li>
 * </ul>
 *
 * <p>All four apply to the bound file <em>and its transitive imports</em>
 * (§3.15 "Scope of Bind-Time Checks"): a misplaced annotation declared in
 * an imported {@code .proto} is reported through any descriptor that
 * imports it, attributed to the file that declares it. A file reached
 * twice — the diamond, A imports B and C, both importing D — is checked
 * once.
 *
 * <p>Enforcement runs at descriptor-bind time inside
 * {@link UnmarshalOptions#unmarshal(byte[], com.google.protobuf.Message.Builder)} /
 * {@link UnmarshalOptions#unmarshalFull(byte[], com.google.protobuf.Message.Builder)}.
 * Callers that have already validated their descriptors (codegen
 * pre-screening, registry-load passes) can set
 * {@link UnmarshalOptions#skipValidate()} to bypass the per-call recheck.
 * Results are memoized per descriptor (see {@link #validateFile}), so the
 * closure walk costs less than the single-file walk it replaced.
 *
 * <p>The reserved-name check is case-sensitive: identifiers such as
 * {@code "NULL"} or {@code "True"} lex as ordinary identifiers and are
 * accepted.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /**
     * Reserved-name set per draft §3.14. Case-sensitive — {@code NULL},
     * {@code True}, {@code FALSE} lex as ordinary identifiers and are
     * accepted.
     *
     * <p>The full reserved-directive-name set (13 names; draft §3.4.6) is
     * separate from this schema-element constraint and lives in
     * {@link #FUTURE_RESERVED_DIRECTIVES} — schema-element name
     * collisions with directive names are not problematic because field
     * names and directive names live in disjoint lexical contexts.
     */
    static final Set<String> RESERVED_NAMES = Set.of("null", "true", "false");

    /**
     * Directive names the spec reserves for future allocation (draft
     * §3.4.6). Lives on {@link Parser}, which enforces it and has no
     * descriptor dependency; kept here under its original name.
     */
    public static final Set<String> FUTURE_RESERVED_DIRECTIVES = Parser.FUTURE_RESERVED_DIRECTIVES;

    /** Which bind-time check a {@link Violation} failed. */
    public enum Kind {
        /** A message field whose name is reserved. */
        FIELD("message field"),
        /** A oneof declaration whose name is reserved. */
        ONEOF("oneof"),
        /** An enum value whose name is reserved. */
        ENUM_VALUE("enum value"),
        /**
         * A {@code (pxf.key)} annotation whose placement violates draft -01
         * §3.13.1: the annotated field is not a repeated message-typed
         * field, or the value does not name a singular string field of the
         * element message.
         */
        KEY_OPTION("keyed field option"),
        /**
         * A {@code (pxf.default)} annotation whose placement violates draft
         * -01 §6.1.1 "Default Placement" (one literal cannot denote a list,
         * a map, a group or an arbitrary message) or §6.1.2 "Oneof Members"
         * (at most one member of a oneof may carry one).
         */
        DEFAULT_OPTION("default field option"),
        /**
         * A {@code (pxf.required)} annotation on a member of a oneof, which
         * draft -01 §6.1.2 "Oneof Members" forbids: read per field it
         * demands that one specific arm always be chosen, which makes every
         * other arm of the oneof undecodable.
         */
        REQUIRED_OPTION("required field option"),
        /**
         * An option carried at one of the extension numbers protowire retired
         * when it moved into its registered block (STABILITY.md promise 3):
         * the descriptor was compiled before protowire v1.12.0 and must be
         * recompiled, because a reader that looks only at the registered
         * numbers would otherwise silently see none of its annotations. The
         * violation's name is the retired number.
         */
        RETIRED_NUMBER("retired option number");

        private final String label;

        Kind(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    /**
     * One schema element that fails a bind-time check.
     *
     * @param file    {@code .proto} file path the offending element is
     *                declared in — which, under the closure rule, need not
     *                be the file that was bound
     * @param element fully-qualified protobuf name (e.g.
     *                {@code "trades.v1.Side.null"})
     * @param name    the bare reserved identifier for the reserved-name
     *                kinds; the {@code (pxf.key)} value for
     *                {@link Kind#KEY_OPTION}; the {@code (pxf.default)}
     *                literal for {@link Kind#DEFAULT_OPTION}; the containing
     *                oneof's bare name for {@link Kind#REQUIRED_OPTION}
     * @param kind    which check failed
     * @param detail  a human-readable explanation for the placement kinds;
     *                empty for the reserved-name kinds
     */
    public record Violation(String file, String element, String name, Kind kind, String detail) {
        public Violation {
            if (detail == null) detail = "";
        }

        /** As the five-argument form with an empty {@code detail}. */
        public Violation(String file, String element, String name, Kind kind) {
            this(file, element, name, kind, "");
        }

        @Override public String toString() {
            return switch (kind) {
                case KEY_OPTION -> file + ": field \"" + element + "\": invalid (pxf.key) = \"" + name + "\": "
                        + detail + " (draft -01 §3.13)";
                case DEFAULT_OPTION -> file + ": field \"" + element + "\": invalid (pxf.default) = \"" + name + "\": "
                        + detail + " (draft -01 §annotation-extensions)";
                case REQUIRED_OPTION -> file + ": field \"" + element + "\": invalid (pxf.required): "
                        + detail + " (draft -01 §annotation-extensions)";
                case RETIRED_NUMBER -> file + ": \"" + element + "\": " + detail + " (STABILITY.md promise 3)";
                default -> file + ": " + kind + " \"" + element + "\" uses PXF-reserved name \""
                        + name + "\" (draft §3.13)";
            };
        }
    }

    /**
     * Walk the file containing {@code desc} together with its transitive
     * imports and return every bind-time violation in that closure, sorted
     * by declaring file path and then by element fully-qualified name.
     * Empty list means the schema is conformant. Results are memoized per
     * descriptor; the returned list is immutable.
     */
    public static List<Violation> validateDescriptor(Descriptor desc) {
        if (desc == null) return List.of();
        return validateFile(desc.getFile());
    }

    /**
     * Walk {@code fd} and its transitive imports and return every bind-time
     * violation in that closure (see {@link #validateDescriptor}).
     *
     * <p>Memoized at two grains, because this runs per decode for every
     * caller that does not set {@code skipValidate} and the closure walk is
     * far too expensive to repeat there: every schema using the annotations
     * imports {@code pxf/annotations.proto} and through it
     * {@code google/protobuf/descriptor.proto}, 54 messages and enums no
     * PXF document can name. The whole-closure result per bound file is
     * the hot path; each file's own violations underneath it make a
     * closure-cache miss a traversal rather than a walk, so a registry-load
     * pass over many descriptors pays for {@code descriptor.proto} once.
     * Both caches key on descriptor identity, not file path — two
     * registries can hold different files at the same path — and are
     * bounded, because a live entry pins the descriptor's whole graph and
     * a caller that compiles descriptors per request would otherwise leak.
     * Descriptors are immutable, so a cached result never goes stale.
     */
    public static List<Violation> validateFile(FileDescriptor fd) {
        if (fd == null) return List.of();
        List<Violation> cached = CLOSURE_CACHE.get(fd);
        if (cached != null) return cached;
        List<Violation> out = new ArrayList<>();
        walkClosure(fd, new ArrayList<>(), out);
        // Stable: one field can yield several violations sharing an
        // element (reserved name, key, default placement, a oneof rule).
        // File first, so a multi-file closure reports one file's
        // violations together.
        out.sort(Comparator.comparing(Violation::file).thenComparing(Violation::element));
        List<Violation> result = List.copyOf(out);
        store(CLOSURE_CACHE, CLOSURE_COUNT, fd, result);
        return result;
    }

    /** Bound on each cache; see {@link #validateFile}. */
    static final int MAX_CACHED_DESCRIPTORS = 4096;

    // FileDescriptor does not override equals/hashCode, so these maps key
    // on identity.
    private static final Map<FileDescriptor, List<Violation>> CLOSURE_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger CLOSURE_COUNT = new AtomicInteger();
    private static final Map<FileDescriptor, List<Violation>> FILE_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger FILE_COUNT = new AtomicInteger();

    private static void store(Map<FileDescriptor, List<Violation>> cache, AtomicInteger count,
                              FileDescriptor fd, List<Violation> vs) {
        if (count.get() >= MAX_CACHED_DESCRIPTORS) return;
        if (cache.putIfAbsent(fd, vs) == null) count.incrementAndGet();
    }

    /** Test hook: the number of closures currently memoized. */
    static int cachedClosures() { return CLOSURE_CACHE.size(); }

    /**
     * Deduplication is by path rather than by descriptor identity: within
     * one closure a path names one file, and the diamond is the common
     * shape that would otherwise report D's violations twice. Closures are
     * small, so a linear scan beats a set.
     */
    private static void walkClosure(FileDescriptor fd, List<String> seen, List<Violation> out) {
        String path = fd.getName();
        if (seen.contains(path)) return;
        seen.add(path);
        out.addAll(fileViolations(fd));
        for (FileDescriptor dep : fd.getDependencies()) walkClosure(dep, seen, out);
    }

    /**
     * The violations declared in {@code fd} itself, ignoring its imports.
     * Memoized and shared; immutable.
     */
    static List<Violation> fileViolations(FileDescriptor fd) {
        List<Violation> cached = FILE_CACHE.get(fd);
        if (cached != null) return cached;
        List<Violation> out = new ArrayList<>();
        for (Descriptor m : fd.getMessageTypes()) walkMessage(fd.getName(), m, out);
        for (EnumDescriptor e : fd.getEnumTypes()) walkEnum(fd.getName(), e, out);
        // Retired option numbers are looked for only in files that import
        // protowire's annotations; see RetiredNumbers for why the gate exists.
        if (RetiredNumbers.importsProtowire(fd)) RetiredNumbers.walk(fd.getName(), fd, out);
        List<Violation> result = List.copyOf(out);
        store(FILE_CACHE, FILE_COUNT, fd, result);
        return result;
    }

    private static void walkMessage(String file, Descriptor m, List<Violation> out) {
        // Members of this message's oneofs that carry (pxf.default),
        // collected during the field pass and capped once the fields are
        // known.
        List<FieldDescriptor> oneofDefaults = null;
        for (FieldDescriptor f : m.getFields()) {
            String name = f.getName();
            if (RESERVED_NAMES.contains(name)) {
                out.add(new Violation(file, f.getFullName(), name, Kind.FIELD));
            }
            String key = Annotations.getKey(f);
            if (key != null) checkKeyOption(file, f, key, out);
            String def = Annotations.getDefault(f);
            if (def != null) checkDefaultOption(file, f, def, out);
            OneofDescriptor oo = realOneof(f);
            if (oo != null) {
                if (Annotations.isRequired(f)) {
                    out.add(new Violation(file, f.getFullName(), oo.getName(), Kind.REQUIRED_OPTION,
                            "(pxf.required) is not valid on a member of oneof \"" + oo.getName()
                                    + "\": read per field it demands that one arm always be chosen, "
                                    + "which makes every other arm undecodable"));
                }
                if (def != null) {
                    if (oneofDefaults == null) oneofDefaults = new ArrayList<>();
                    oneofDefaults.add(f);
                }
            }
        }
        if (oneofDefaults != null) checkOneofDefaultCap(file, oneofDefaults, out);
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

    /**
     * {@code f}'s containing oneof, or null when it is not a member of one.
     * A proto3 {@code optional} field sits in a synthetic single-member
     * oneof, which carries none of the semantics the oneof rules are about
     * — nothing else can clear it — so it reports null.
     */
    private static OneofDescriptor realOneof(FieldDescriptor f) {
        OneofDescriptor oo = f.getContainingOneof();
        return oo == null || oo.isSynthetic() ? null : oo;
    }

    /**
     * Draft -01 §3.13.1: {@code (pxf.key)} is valid only on a repeated
     * message-typed field, and its value must name a singular string
     * field of the element message.
     */
    private static void checkKeyOption(String file, FieldDescriptor f, String keyName, List<Violation> out) {
        if (!f.isRepeated() || f.isMapField() || f.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
            out.add(new Violation(file, f.getFullName(), keyName, Kind.KEY_OPTION,
                    "(pxf.key) is valid only on repeated message-typed fields"));
            return;
        }
        FieldDescriptor kf = f.getMessageType().findFieldByName(keyName);
        if (kf == null) {
            out.add(new Violation(file, f.getFullName(), keyName, Kind.KEY_OPTION,
                    "element message " + f.getMessageType().getFullName() + " has no field \"" + keyName + "\""));
            return;
        }
        if (kf.isRepeated() || kf.getType() != FieldDescriptor.Type.STRING) {
            out.add(new Violation(file, f.getFullName(), keyName, Kind.KEY_OPTION,
                    "key field " + kf.getFullName() + " must be a singular string field"));
        }
    }

    /**
     * Draft -01 §6.1.1 "Default Placement": {@code (pxf.default)} carries
     * exactly one PXF literal, so it is valid only on fields a single
     * literal can denote — singular scalars, enums, and the message types
     * {@link WellKnown#isDefaultableMessage} names. Placement only: a
     * literal that does not parse as the field's type stays a decode-time
     * error, since placement is decidable from the descriptor alone and the
     * literal is not without running the value parser here. The rejected
     * set is exactly the set {@code FastDecoder.applyDefault} cannot honor.
     */
    private static void checkDefaultOption(String file, FieldDescriptor f, String def, List<Violation> out) {
        String detail;
        if (f.isMapField()) {
            detail = "(pxf.default) is not valid on map fields: one literal cannot denote a map";
        } else if (f.isRepeated()) {
            detail = "(pxf.default) is not valid on repeated fields: one literal cannot denote a list";
        } else if (f.getType() == FieldDescriptor.Type.GROUP) {
            detail = "(pxf.default) is not valid on group fields";
        } else if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && !WellKnown.isDefaultableMessage(f.getMessageType())) {
            detail = "(pxf.default) is not valid on message type " + f.getMessageType().getFullName()
                    + ": no PXF literal denotes it";
        } else {
            return;
        }
        out.add(new Violation(file, f.getFullName(), def, Kind.DEFAULT_OPTION, detail));
    }

    /**
     * Draft -01 §6.1.2 "Oneof Members": at most one member of any one oneof
     * may carry {@code (pxf.default)}. With two, some default must win, and
     * deciding by declaration order or field number would attach meaning to
     * a detail authors are free to change — so the schema is rejected.
     * Reported on every offending member, so the author sees each
     * annotation to remove.
     */
    private static void checkOneofDefaultCap(String file, List<FieldDescriptor> annotated, List<Violation> out) {
        if (annotated.size() < 2) return;
        Map<String, List<FieldDescriptor>> byOneof = new HashMap<>();
        for (FieldDescriptor f : annotated) {
            byOneof.computeIfAbsent(f.getContainingOneof().getFullName(), k -> new ArrayList<>()).add(f);
        }
        for (FieldDescriptor f : annotated) {
            List<FieldDescriptor> members = byOneof.get(f.getContainingOneof().getFullName());
            if (members.size() < 2 || members.get(0) != f) continue; // conformant, or already reported
            StringBuilder names = new StringBuilder();
            for (FieldDescriptor m : members) names.append(names.length() > 0 ? ", " : "").append(m.getName());
            String detail = "at most one member of oneof \"" + f.getContainingOneof().getName()
                    + "\" may carry a default; " + members.size() + " do (" + names + ")";
            for (FieldDescriptor m : members) {
                out.add(new Violation(file, m.getFullName(), Annotations.getDefault(m), Kind.DEFAULT_OPTION, detail));
            }
        }
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
        StringBuilder sb = new StringBuilder("PXF schema bind-time violations:");
        for (Violation v : violations) {
            sb.append("\n  ").append(v);
        }
        return sb.toString();
    }
}
