package org.protowire.pxf.android;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;
import org.protowire.pxf.Position;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes a parsed PXF {@link Ast.Document} to raw protobuf wire bytes,
 * driven by per-message tables in a {@link PxfMeta}. The output bytes are
 * what {@code MessageLite.parseFrom(...)} expects, so a typical caller flow
 * is:
 *
 * <pre>{@code
 * Ast.Document doc   = new Parser(...).parse();
 * byte[]       bytes = LiteWireWriter.encode(doc, FooPxfMeta.INSTANCE);
 * Foo          msg   = Foo.parseFrom(bytes);
 * }</pre>
 *
 * <p><b>Scope.</b> Handles every protobuf scalar type ({@code int32/64},
 * {@code uint32/64}, {@code sint32/64}, {@code fixed32/64},
 * {@code sfixed32/64}, {@code bool}, {@code float}, {@code double},
 * {@code string}, {@code bytes}), {@code repeated} scalars with proto3
 * default-packing, nested messages (recursive — looked up by FQN through a
 * caller-supplied {@link PxfRegistry}), and enum-typed fields (identifier
 * tokens are translated to integers via the registry's {@link PxfEnum};
 * integer literals are also accepted, mirroring PXF's grammar).
 *
 * <p>Maps, oneof mutual-exclusion, {@code (pxf.required)} validation,
 * {@code (pxf.default)} application, and the well-known types are all
 * handled — both Google's standard set
 * ({@code Timestamp}, {@code Duration}, the {@code *Value} scalar
 * wrappers) and the protowire-specific arbitrary-precision number types
 * ({@code pxf.BigInt}, {@code pxf.Decimal}, {@code pxf.BigFloat}). The
 * encoder is feature-complete for the lite tier as far as the marshal
 * direction is concerned; the symmetric reader path / PXF text writer
 * is still queued.
 *
 * <p>The PXF {@code null} sentinel ({@code field = null}) marks a field as
 * present for {@code (pxf.required)} validation purposes but emits no wire
 * bytes — the canonical way to "explicitly absent" a wrapper or proto3
 * optional field in PXF text.
 *
 * <p>Two encode overloads, plus an embedded-lookup fast path:
 * <ul>
 *   <li>{@link #encode(Ast.Document, PxfMeta)} — works whenever the
 *       supplied {@link PxfMeta} carries its own nested-message and enum
 *       references via {@link PxfMeta#nestedMetas()} / {@link PxfMeta#enumMetas()}.
 *       Codegen-generated {@code <Message>PxfMeta} classes populate those
 *       maps with direct {@code <Sub>PxfMeta.INSTANCE} references, so the
 *       common case is "pass {@code FooPxfMeta.INSTANCE}, no registry
 *       needed". Throws if a nested or enum lookup misses the embedded map
 *       and would have to fall back to a registry that wasn't supplied.</li>
 *   <li>{@link #encode(Ast.Document, PxfMeta, PxfRegistry)} — full version;
 *       the registry resolves nested {@link PxfMeta} and {@link PxfEnum}
 *       references by their {@code fullName()} when the embedded maps don't
 *       have them. Useful for hand-built {@link PxfMeta} implementations,
 *       test fixtures, and cross-module references that aren't pre-linked
 *       at codegen time.</li>
 * </ul>
 */
public final class LiteWireWriter {
    private LiteWireWriter() {}

    // FieldDescriptorProto.Type enum integers (stable, see descriptor.proto).
    private static final int K_DOUBLE   = 1;
    private static final int K_FLOAT    = 2;
    private static final int K_INT64    = 3;
    private static final int K_UINT64   = 4;
    private static final int K_INT32    = 5;
    private static final int K_FIXED64  = 6;
    private static final int K_FIXED32  = 7;
    private static final int K_BOOL     = 8;
    private static final int K_STRING   = 9;
    private static final int K_MESSAGE  = 11;
    private static final int K_BYTES    = 12;
    private static final int K_UINT32   = 13;
    private static final int K_ENUM     = 14;
    private static final int K_SFIXED32 = 15;
    private static final int K_SFIXED64 = 16;
    private static final int K_SINT32   = 17;
    private static final int K_SINT64   = 18;

    /**
     * Sentinel registry used by the 2-arg {@link #encode(Ast.Document, PxfMeta)}
     * overload. Throws on every lookup so messages that genuinely need a
     * registry (any with nested-message or enum-typed fields) fail fast with
     * a clear pointer to the 3-arg overload.
     */
    private static final PxfRegistry REGISTRY_REQUIRED = new PxfRegistry() {
        @Override public PxfMeta lookupMessage(String fullName) {
            throw new IllegalStateException(
                "encoding requires a PxfRegistry to resolve nested message '" + fullName +
                "' — call encode(doc, meta, registry) with one registered");
        }
        @Override public PxfEnum lookupEnum(String fullName) {
            throw new IllegalStateException(
                "encoding requires a PxfRegistry to resolve enum '" + fullName +
                "' — call encode(doc, meta, registry) with one registered");
        }
    };

    /**
     * Encode a top-level PXF document into protobuf wire bytes. Use this
     * overload only for messages whose schemas have no nested-message and no
     * enum-typed fields — those paths require a {@link PxfRegistry} for FQN
     * resolution, and this overload's sentinel registry will throw if hit.
     */
    public static byte[] encode(Ast.Document doc, PxfMeta meta) {
        return encode(doc, meta, REGISTRY_REQUIRED);
    }

    /**
     * Encode a top-level PXF document into protobuf wire bytes. The registry
     * resolves nested {@link PxfMeta} and {@link PxfEnum} references by the
     * full names declared in {@code meta.messageTypes()} / {@code enumTypes()}.
     */
    public static byte[] encode(Ast.Document doc, PxfMeta meta, PxfRegistry registry) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buffer);
        // Track which field numbers were set in the input + which oneof slots
        // got claimed; needed for default application, oneof mutual-exclusion
        // checks, and required-field validation.
        Set<Integer> setFields = new HashSet<>();
        Map<String, Integer> oneofSet = new HashMap<>();
        writeEntries(out, doc.entries(), meta, registry, setFields, oneofSet);
        applyDefaults(out, meta, registry, setFields, oneofSet);
        validateRequired(meta, setFields);
        try {
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream flush failed", e);
        }
        return buffer.toByteArray();
    }

    private static void writeEntries(
            CodedOutputStream out,
            List<Ast.Entry> entries,
            PxfMeta meta,
            PxfRegistry registry,
            Set<Integer> setFields,
            Map<String, Integer> oneofSet) {
        Map<String, Integer> fieldNumbers = meta.fieldNumbers();
        Map<Integer, Integer> fieldKinds = meta.fieldKinds();
        Set<Integer> repeated = meta.repeatedFields();
        Set<Integer> packed = meta.packedFields();
        Map<Integer, String> oneofOf = meta.oneofOf();

        for (Ast.Entry entry : entries) {
            switch (entry) {
                case Ast.Assignment a -> {
                    Integer num = fieldNumbers.get(a.key());
                    if (num == null) {
                        throw new IllegalArgumentException("unknown field name: " + a.key());
                    }
                    checkOneofCollision(num, a.key(), meta, oneofOf, oneofSet);
                    int kind = fieldKinds.getOrDefault(num, 0);
                    // PXF `field = null` sentinel: the field counts as present
                    // for (pxf.required) validation per the annotation's
                    // Javadoc, but no wire bytes are emitted. This is the
                    // canonical way to "explicitly absent" a wrapper or
                    // proto3 optional field in PXF.
                    if (a.value() instanceof Ast.NullVal) {
                        recordSet(num, oneofOf, setFields, oneofSet);
                        break;
                    }
                    if (repeated.contains(num) && a.value() instanceof Ast.ListVal list) {
                        writeRepeated(out, num, kind, list, packed.contains(num), meta, registry);
                    } else {
                        writeField(out, num, kind, a.value(), meta, registry);
                    }
                    recordSet(num, oneofOf, setFields, oneofSet);
                }
                case Ast.Block b -> {
                    Integer num = fieldNumbers.get(b.name());
                    if (num == null) {
                        throw new IllegalArgumentException("unknown field name: " + b.name());
                    }
                    checkOneofCollision(num, b.name(), meta, oneofOf, oneofSet);
                    int kind = fieldKinds.getOrDefault(num, 0);
                    if (meta.mapFields().contains(num)) {
                        writeMap(out, num, b.entries(), meta, registry);
                    } else if (kind == K_MESSAGE) {
                        writeNestedMessage(out, num, b.entries(), meta, registry);
                    } else {
                        throw new IllegalArgumentException(
                            "block syntax requires a message-typed field; '" + b.name() +
                            "' has kind " + kind);
                    }
                    recordSet(num, oneofOf, setFields, oneofSet);
                }
                case Ast.MapEntry m -> throw new IllegalArgumentException(
                    "map entry '" + m.key() + "' appears outside a map field block in " +
                    meta.fullName());
            }
        }
    }

    /** Records that field {@code num} was set; updates the oneof slot map if applicable. */
    private static void recordSet(
            int num,
            Map<Integer, String> oneofOf,
            Set<Integer> setFields,
            Map<String, Integer> oneofSet) {
        setFields.add(num);
        String oneof = oneofOf.get(num);
        if (oneof != null) {
            oneofSet.put(oneof, num);
        }
    }

    /**
     * Detects a oneof mutual-exclusion violation: assigning to a field whose
     * oneof slot already holds a different field number. Throws
     * {@link IllegalArgumentException} with a message naming both fields and
     * the containing message's full name.
     */
    private static void checkOneofCollision(
            int num,
            String fieldName,
            PxfMeta meta,
            Map<Integer, String> oneofOf,
            Map<String, Integer> oneofSet) {
        String oneof = oneofOf.get(num);
        if (oneof == null) {
            return;
        }
        Integer existing = oneofSet.get(oneof);
        if (existing == null || existing.equals(num)) {
            return;
        }
        throw new IllegalArgumentException(
            "oneof '" + oneof + "' in " + meta.fullName() +
            " already has '" + fieldNameFor(meta, existing) +
            "' set; cannot also set '" + fieldName + "'");
    }

    /** Reverse-lookup of a field name from its number, for diagnostic messages. */
    private static String fieldNameFor(PxfMeta meta, int num) {
        for (Map.Entry<String, Integer> e : meta.fieldNumbers().entrySet()) {
            if (e.getValue() == num) {
                return e.getKey();
            }
        }
        return "<field #" + num + ">";
    }

    /**
     * Walks {@link PxfMeta#defaults()} and emits the default value for any
     * field that wasn't set in the input. Skips fields whose oneof slot is
     * already occupied (a oneof default can't override a sibling that was
     * explicitly set). Defaults are themselves recorded as set so subsequent
     * required-field validation accepts them.
     *
     * <p>Default literals are PXF text fragments (e.g. {@code "42"},
     * {@code "STATUS_ACTIVE"}, {@code "\"hi\""}); we run them through the
     * existing PXF parser by wrapping as a synthetic assignment, which is
     * the minimum-friction way to reuse the lexer's literal handling.
     */
    private static void applyDefaults(
            CodedOutputStream out,
            PxfMeta meta,
            PxfRegistry registry,
            Set<Integer> setFields,
            Map<String, Integer> oneofSet) {
        Map<Integer, String> defaults = meta.defaults();
        if (defaults.isEmpty()) {
            return;
        }
        Map<Integer, Integer> fieldKinds = meta.fieldKinds();
        Map<Integer, String> oneofOf = meta.oneofOf();
        for (Map.Entry<Integer, String> e : defaults.entrySet()) {
            int num = e.getKey();
            if (setFields.contains(num)) {
                continue;
            }
            String oneof = oneofOf.get(num);
            if (oneof != null && oneofSet.containsKey(oneof)) {
                continue;
            }
            Ast.Document parsed = Parser.parse("__default = " + e.getValue());
            if (parsed.entries().isEmpty() || !(parsed.entries().get(0) instanceof Ast.Assignment def)) {
                throw new IllegalStateException(
                    "(pxf.default) for field " + fieldNameFor(meta, num) + " in " + meta.fullName() +
                    " did not parse as a value: " + e.getValue());
            }
            int kind = fieldKinds.getOrDefault(num, 0);
            writeField(out, num, kind, def.value(), meta, registry);
            recordSet(num, oneofOf, setFields, oneofSet);
        }
    }

    /**
     * Throws if any {@link PxfMeta#requiredFields()} entry isn't in
     * {@code setFields} — must be called AFTER {@link #applyDefaults} so a
     * default-bearing field counts as satisfied.
     */
    private static void validateRequired(PxfMeta meta, Set<Integer> setFields) {
        Set<Integer> required = meta.requiredFields();
        if (required.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Integer req : required) {
            if (!setFields.contains(req)) {
                missing.add(fieldNameFor(meta, req));
            }
        }
        if (!missing.isEmpty()) {
            Collections.sort(missing);
            throw new IllegalArgumentException(
                "required field(s) missing in " + meta.fullName() + ": " +
                String.join(", ", missing));
        }
    }

    /**
     * Single-value field write — dispatches {@link #writeScalar} for scalar
     * kinds, plus the enum (translate identifier → int via registry) and
     * message (single-element-into-repeated-block-style) paths.
     */
    private static void writeField(
            CodedOutputStream out,
            int num,
            int kind,
            Ast.Value v,
            PxfMeta meta,
            PxfRegistry registry) {
        if (kind == K_ENUM) {
            writeEnum(out, num, v, meta, registry);
            return;
        }
        if (kind == K_MESSAGE) {
            // Well-known type fast paths: when the user writes a bare timestamp
            // or duration literal (`created = 2024-01-15T10:30:00Z`), the lexer
            // produced a TimestampVal/DurationVal — we emit the well-known
            // (seconds, nanos) submessage shape directly without recursing.
            if (v instanceof Ast.TimestampVal tv) {
                requireWellKnownTarget(num, meta, "com.google.protobuf.Timestamp", "timestamp");
                writeWellKnownSecondsNanos(out, num, tv.value().getEpochSecond(), tv.value().getNano());
                return;
            }
            if (v instanceof Ast.DurationVal dv) {
                requireWellKnownTarget(num, meta, "com.google.protobuf.Duration", "duration");
                writeWellKnownSecondsNanos(out, num, dv.value().getSeconds(), dv.value().getNano());
                return;
            }
            // PXF bignum + Google *Value wrapper fast paths, all keyed on the
            // field's target Java FQN. Bignums (BigInt / Decimal / BigFloat)
            // accept bare integer or float literals; wrappers box a bare
            // scalar into a {value=1: <scalar>} submessage.
            String fqn = meta.messageTypes().get(num);
            if ("org.protowire.proto.pxf.BigInt".equals(fqn)) {
                writeBigInt(out, num, parseBigInteger(v, meta, num));
                return;
            }
            if ("org.protowire.proto.pxf.Decimal".equals(fqn)) {
                writeDecimal(out, num, parseBigDecimal(v, meta, num));
                return;
            }
            if ("org.protowire.proto.pxf.BigFloat".equals(fqn)) {
                writeBigFloat(out, num, parseBigDecimal(v, meta, num));
                return;
            }
            if (!(v instanceof Ast.BlockVal)) {
                Integer wrapperKind = WRAPPER_INNER_KIND.get(fqn);
                if (wrapperKind != null) {
                    writeWellKnownValueWrapper(out, num, wrapperKind, v);
                    return;
                }
            }
            // PXF allows `field = { ... }` (BlockVal) as an alternative to the bare-block form.
            if (v instanceof Ast.BlockVal bv) {
                writeNestedMessage(out, num, bv.entries(), meta, registry);
                return;
            }
            throw new IllegalArgumentException(
                "field of kind MESSAGE requires a block value; got " + v.getClass().getSimpleName());
        }
        writeScalar(out, num, kind, v);
    }

    /**
     * Map from the Java FQN of each {@code google.protobuf.*Value} wrapper to
     * the wire kind of its single {@code value} field at index 1. Drives the
     * wrapper fast path in {@link #writeField}.
     */
    private static final Map<String, Integer> WRAPPER_INNER_KIND = Map.ofEntries(
        Map.entry("com.google.protobuf.StringValue", K_STRING),
        Map.entry("com.google.protobuf.BytesValue",  K_BYTES),
        Map.entry("com.google.protobuf.BoolValue",   K_BOOL),
        Map.entry("com.google.protobuf.Int32Value",  K_INT32),
        Map.entry("com.google.protobuf.Int64Value",  K_INT64),
        Map.entry("com.google.protobuf.UInt32Value", K_UINT32),
        Map.entry("com.google.protobuf.UInt64Value", K_UINT64),
        Map.entry("com.google.protobuf.FloatValue",  K_FLOAT),
        Map.entry("com.google.protobuf.DoubleValue", K_DOUBLE)
    );

    /**
     * Wraps a bare scalar as a {@code google.protobuf.*Value} submessage at
     * field {@code num}. The wrapper has a single {@code value} field at
     * index 1 with kind {@code innerKind}; we delegate to {@link #writeScalar}
     * via a temp {@link ByteArrayOutputStream} so we know the payload length
     * before emitting the outer length-delimited record. Default-zero scalars
     * encode as an empty payload (e.g. {@code Int32Value} for {@code 0}
     * produces {@code 0a 00}), matching protobuf's standard wrapper semantics.
     */
    private static void writeWellKnownValueWrapper(
            CodedOutputStream out, int num, int innerKind, Ast.Value v) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        // Proto3 wraps default-value scalars by omitting the inner field —
        // Int32Value(0) serializes to empty payload bytes, not `08 00`. Match
        // that so wire output stays equivalent to what protobuf-java's
        // generated MessageLite.toByteArray() would produce.
        if (!isDefaultScalar(innerKind, v)) {
            writeScalar(payloadOut, 1, innerKind, v);
        }
        try {
            payloadOut.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream flush failed", e);
        }
        byte[] payload = payloadBuf.toByteArray();
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(payload.length);
            out.writeRawBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    /**
     * Detects whether a value is the proto3 default for the given scalar kind
     * — used by the wrapper encoder to omit default-zero inner fields, since
     * proto3 doesn't emit default-valued scalars on the wire.
     */
    private static boolean isDefaultScalar(int kind, Ast.Value v) {
        return switch (kind) {
            case K_BOOL -> !asBool(v);
            case K_INT32, K_INT64, K_UINT32, K_UINT64,
                 K_SINT32, K_SINT64,
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64 -> asLong(v) == 0L;
            case K_FLOAT  -> asFloat(v) == 0.0f;
            case K_DOUBLE -> asDouble(v) == 0.0;
            case K_STRING -> asString(v).isEmpty();
            case K_BYTES  -> asBytes(v).length == 0;
            default -> false;
        };
    }

    /**
     * Confirms a TimestampVal / DurationVal landed on a field whose target
     * Java type is the matching well-known wrapper. Catches user errors like
     * sticking a timestamp literal into a hand-written submessage that
     * happens to be MESSAGE-typed but isn't actually
     * {@code google.protobuf.Timestamp} — without this check the encoder
     * would silently produce wire bytes structured like a Timestamp into a
     * field that expects something else entirely.
     */
    private static void requireWellKnownTarget(
            int num, PxfMeta meta, String expectedJavaFqn, String literalKind) {
        String actual = meta.messageTypes().get(num);
        if (!expectedJavaFqn.equals(actual)) {
            throw new IllegalArgumentException(
                literalKind + " literal at field " + num + " of " + meta.fullName() +
                " requires " + expectedJavaFqn + "; messageTypes() reports " +
                (actual == null ? "<unset>" : actual));
        }
    }

    /**
     * Writes a {@code google.protobuf.Timestamp} / {@code Duration}-shaped
     * submessage: a length-delimited record at field {@code num} whose
     * payload is {@code seconds} (int64, field 1) and {@code nanos}
     * (int32, field 2). Default-zero components are omitted per protobuf
     * convention, so the all-zero case writes a zero-byte payload.
     */
    private static void writeWellKnownSecondsNanos(
            CodedOutputStream out, int num, long seconds, int nanos) {
        int payloadSize = 0;
        if (seconds != 0L) {
            payloadSize += CodedOutputStream.computeInt64Size(1, seconds);
        }
        if (nanos != 0) {
            payloadSize += CodedOutputStream.computeInt32Size(2, nanos);
        }
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(payloadSize);
            if (seconds != 0L) {
                out.writeInt64(1, seconds);
            }
            if (nanos != 0) {
                out.writeInt32(2, nanos);
            }
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    private static void writeNestedMessage(
            CodedOutputStream out,
            int num,
            List<Ast.Entry> entries,
            PxfMeta meta,
            PxfRegistry registry) {
        // Prefer the meta's embedded nested reference (populated by codegen)
        // over a registry lookup. This is the path for codegen-generated
        // <Message>PxfMeta classes; registry is the fallback for hand-built
        // PxfMeta implementations or test fixtures that don't pre-link.
        PxfMeta nestedMeta = meta.nestedMetas().get(num);
        if (nestedMeta == null) {
            String typeFqn = meta.messageTypes().get(num);
            if (typeFqn == null) {
                throw new IllegalStateException(
                    "MESSAGE_TYPES table missing entry for field number " + num + " in " + meta.fullName());
            }
            nestedMeta = registry.lookupMessage(typeFqn);
            if (nestedMeta == null) {
                throw new IllegalArgumentException(
                    "PxfRegistry has no entry for nested message '" + typeFqn +
                    "' (referenced from field " + num + " of " + meta.fullName() + ")");
            }
        }
        // Encode the nested entries to a side buffer first so we know the byte length.
        byte[] nestedBytes = encode(new Ast.Document("", entries, List.of()), nestedMeta, registry);
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(nestedBytes.length);
            out.writeRawBytes(nestedBytes);
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    /**
     * Encodes a {@code map<K, V>} field. Each {@link Ast.MapEntry} produces
     * a length-delimited record on the wire: the same field number repeated
     * once per entry, with each payload being a synthetic 2-field message
     * carrying {@code key = 1} and {@code value = 2}.
     *
     * <p>Reuses {@link #encode(Ast.Document, PxfMeta, PxfRegistry)} for the
     * per-entry encoding by building a synthetic {@link Ast.Document}
     * containing the key + value as ordinary assignments. That keeps the
     * map path free of duplicated scalar/message/enum dispatch logic — the
     * recursive call lands back in {@code writeEntries} against the entry
     * sub-message's PxfMeta.
     */
    private static void writeMap(
            CodedOutputStream out,
            int num,
            List<Ast.Entry> entries,
            PxfMeta meta,
            PxfRegistry registry) {
        PxfMeta entryMeta = meta.nestedMetas().get(num);
        if (entryMeta == null) {
            String typeFqn = meta.messageTypes().get(num);
            if (typeFqn != null) {
                entryMeta = registry.lookupMessage(typeFqn);
            }
            if (entryMeta == null) {
                throw new IllegalArgumentException(
                    "no PxfMeta available for map entry of field " + num +
                    " in " + meta.fullName() + " (looked in nestedMetas + registry)");
            }
        }
        int keyKind = entryMeta.fieldKinds().getOrDefault(1, K_STRING);

        for (Ast.Entry entry : entries) {
            if (!(entry instanceof Ast.MapEntry me)) {
                throw new IllegalArgumentException(
                    "expected `key: value` map entry, got " + entry.getClass().getSimpleName() +
                    " in map field of " + meta.fullName());
            }
            Ast.Value keyVal = mapKeyValue(me.key(), keyKind, me.pos());
            Ast.Document entryDoc = new Ast.Document("",
                List.of(
                    new Ast.Assignment(me.pos(), "key", keyVal, List.of(), ""),
                    new Ast.Assignment(me.pos(), "value", me.value(), List.of(), "")
                ),
                List.of()
            );
            byte[] entryBytes = encode(entryDoc, entryMeta, registry);
            try {
                out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
                out.writeUInt32NoTag(entryBytes.length);
                out.writeRawBytes(entryBytes);
            } catch (IOException e) {
                throw new IllegalStateException("CodedOutputStream write failed", e);
            }
        }
    }

    /**
     * Wraps a map-key string (as the parser stored it — unquoted for STRING,
     * digit-form for INT-family, "true"/"false" for BOOL) as the appropriate
     * {@link Ast.Value} for the synthetic entry document. Mirrors the
     * full-runtime decoder's {@code decodeMapKey} contract.
     */
    private static Ast.Value mapKeyValue(String key, int keyKind, Position pos) {
        return switch (keyKind) {
            case K_STRING -> new Ast.StringVal(pos, key);
            case K_INT32, K_INT64, K_UINT32, K_UINT64,
                 K_SINT32, K_SINT64,
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64 -> new Ast.IntVal(pos, key);
            case K_BOOL -> new Ast.BoolVal(pos, Boolean.parseBoolean(key));
            default -> throw new IllegalArgumentException(
                "unsupported map key kind: " + keyKind +
                " (proto restricts map keys to integral, bool, or string types)");
        };
    }

    private static void writeEnum(
            CodedOutputStream out,
            int num,
            Ast.Value v,
            PxfMeta meta,
            PxfRegistry registry) {
        int enumValue;
        if (v instanceof Ast.IdentVal id) {
            // Prefer embedded enum reference; fall back to registry lookup.
            PxfEnum enumMeta = meta.enumMetas().get(num);
            if (enumMeta == null) {
                String typeFqn = meta.enumTypes().get(num);
                if (typeFqn == null) {
                    throw new IllegalStateException(
                        "ENUM_TYPES table missing entry for field number " + num + " in " + meta.fullName());
                }
                enumMeta = registry.lookupEnum(typeFqn);
                if (enumMeta == null) {
                    throw new IllegalArgumentException(
                        "PxfRegistry has no entry for enum '" + typeFqn +
                        "' (referenced from field " + num + " of " + meta.fullName() + ")");
                }
            }
            Integer mapped = enumMeta.values().get(id.name());
            if (mapped == null) {
                throw new IllegalArgumentException(
                    "unknown enum value '" + id.name() + "' for " + enumMeta.fullName());
            }
            enumValue = mapped;
        } else if (v instanceof Ast.IntVal i) {
            // PXF accepts a bare integer for an enum field — used for unknown / forward-compat values.
            enumValue = (int) Long.parseLong(i.raw());
        } else {
            throw new IllegalArgumentException(
                "expected enum identifier or integer literal, got " + v.getClass().getSimpleName());
        }
        try {
            out.writeEnum(num, enumValue);
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    private static void writeScalar(CodedOutputStream out, int num, int kind, Ast.Value v) {
        try {
            switch (kind) {
                case K_STRING   -> out.writeString(num, asString(v));
                case K_BYTES    -> out.writeByteArray(num, asBytes(v));
                case K_BOOL     -> out.writeBool(num, asBool(v));
                case K_INT32    -> out.writeInt32(num, (int) asLong(v));
                case K_INT64    -> out.writeInt64(num, asLong(v));
                case K_UINT32   -> out.writeUInt32(num, (int) asLong(v));
                case K_UINT64   -> out.writeUInt64(num, asLong(v));
                case K_SINT32   -> out.writeSInt32(num, (int) asLong(v));
                case K_SINT64   -> out.writeSInt64(num, asLong(v));
                case K_FIXED32  -> out.writeFixed32(num, (int) asLong(v));
                case K_FIXED64  -> out.writeFixed64(num, asLong(v));
                case K_SFIXED32 -> out.writeSFixed32(num, (int) asLong(v));
                case K_SFIXED64 -> out.writeSFixed64(num, asLong(v));
                case K_FLOAT    -> out.writeFloat(num, asFloat(v));
                case K_DOUBLE   -> out.writeDouble(num, asDouble(v));
                default -> throw new UnsupportedOperationException(
                    "field kind " + kind + " is not yet supported by the lite-runtime encoder");
            }
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    private static void writeRepeated(
            CodedOutputStream out,
            int num,
            int kind,
            Ast.ListVal list,
            boolean packed,
            PxfMeta meta,
            PxfRegistry registry) {
        if (!packed) {
            for (Ast.Value v : list.elements()) {
                writeField(out, num, kind, v, meta, registry);
            }
            return;
        }
        // Packed encoding: tag(num, LEN) + total length + concatenated value bytes (no tags).
        int payloadSize = computePackedPayloadSize(kind, list.elements());
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(payloadSize);
            for (Ast.Value v : list.elements()) {
                writeScalarNoTag(out, kind, v);
            }
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    private static int computePackedPayloadSize(int kind, List<Ast.Value> elements) {
        int total = 0;
        for (Ast.Value v : elements) {
            total += computeScalarSizeNoTag(kind, v);
        }
        return total;
    }

    private static int computeScalarSizeNoTag(int kind, Ast.Value v) {
        return switch (kind) {
            case K_BOOL                                -> 1;
            case K_INT32                               -> CodedOutputStream.computeInt32SizeNoTag((int) asLong(v));
            case K_INT64                               -> CodedOutputStream.computeInt64SizeNoTag(asLong(v));
            case K_UINT32                              -> CodedOutputStream.computeUInt32SizeNoTag((int) asLong(v));
            case K_UINT64                              -> CodedOutputStream.computeUInt64SizeNoTag(asLong(v));
            case K_SINT32                              -> CodedOutputStream.computeSInt32SizeNoTag((int) asLong(v));
            case K_SINT64                              -> CodedOutputStream.computeSInt64SizeNoTag(asLong(v));
            case K_FIXED32, K_SFIXED32, K_FLOAT        -> 4;
            case K_FIXED64, K_SFIXED64, K_DOUBLE       -> 8;
            default -> throw new UnsupportedOperationException(
                "packed encoding for kind " + kind + " is not supported (string/bytes/message/group can't pack)");
        };
    }

    private static void writeScalarNoTag(CodedOutputStream out, int kind, Ast.Value v) throws IOException {
        switch (kind) {
            case K_BOOL     -> out.writeBoolNoTag(asBool(v));
            case K_INT32    -> out.writeInt32NoTag((int) asLong(v));
            case K_INT64    -> out.writeInt64NoTag(asLong(v));
            case K_UINT32   -> out.writeUInt32NoTag((int) asLong(v));
            case K_UINT64   -> out.writeUInt64NoTag(asLong(v));
            case K_SINT32   -> out.writeSInt32NoTag((int) asLong(v));
            case K_SINT64   -> out.writeSInt64NoTag(asLong(v));
            case K_FIXED32  -> out.writeFixed32NoTag((int) asLong(v));
            case K_FIXED64  -> out.writeFixed64NoTag(asLong(v));
            case K_SFIXED32 -> out.writeSFixed32NoTag((int) asLong(v));
            case K_SFIXED64 -> out.writeSFixed64NoTag(asLong(v));
            case K_FLOAT    -> out.writeFloatNoTag(asFloat(v));
            case K_DOUBLE   -> out.writeDoubleNoTag(asDouble(v));
            default -> throw new UnsupportedOperationException(
                "no-tag write for kind " + kind + " is not supported");
        }
    }

    // --- AST value coercion helpers ---------------------------------------
    //
    // Lenient conversions in the spirit of the existing :pxf encoder:
    // strings can drop into bytes fields (re-encoded as UTF-8), int literals
    // can land in float/double slots, etc. Mismatches throw with a message
    // that names the offending field and value kind.

    private static String asString(Ast.Value v) {
        if (v instanceof Ast.StringVal s) return s.value();
        throw new IllegalArgumentException("expected string, got " + v.getClass().getSimpleName());
    }

    private static byte[] asBytes(Ast.Value v) {
        if (v instanceof Ast.BytesVal b) return b.value();
        if (v instanceof Ast.StringVal s) return s.value().getBytes(StandardCharsets.UTF_8);
        throw new IllegalArgumentException("expected bytes, got " + v.getClass().getSimpleName());
    }

    private static boolean asBool(Ast.Value v) {
        if (v instanceof Ast.BoolVal b) return b.value();
        throw new IllegalArgumentException("expected bool, got " + v.getClass().getSimpleName());
    }

    private static long asLong(Ast.Value v) {
        if (v instanceof Ast.IntVal i) return Long.parseLong(i.raw());
        throw new IllegalArgumentException("expected integer, got " + v.getClass().getSimpleName());
    }

    private static float asFloat(Ast.Value v) {
        if (v instanceof Ast.FloatVal f) return Float.parseFloat(f.raw());
        if (v instanceof Ast.IntVal   i) return Float.parseFloat(i.raw());
        throw new IllegalArgumentException("expected float, got " + v.getClass().getSimpleName());
    }

    private static double asDouble(Ast.Value v) {
        if (v instanceof Ast.FloatVal f) return Double.parseDouble(f.raw());
        if (v instanceof Ast.IntVal   i) return Double.parseDouble(i.raw());
        throw new IllegalArgumentException("expected double, got " + v.getClass().getSimpleName());
    }

    // --- PXF bignum helpers -----------------------------------------------
    //
    // pxf.BigInt / pxf.Decimal / pxf.BigFloat all share the same encoding
    // strategy: take an arbitrary-precision Java number, split into
    // unsigned big-endian magnitude bytes (sign-trimmed) plus a separate
    // negative flag, and emit alongside whatever per-type modifiers (scale,
    // exponent, prec) the schema declares. The wire output mirrors what
    // :pxf's WellKnown.set{BigInt,Decimal,BigFloat} produces in the full-
    // runtime tier, so a value flowing JVM → wire → Android → wire round-
    // trips byte-for-byte.

    private static BigInteger parseBigInteger(Ast.Value v, PxfMeta meta, int num) {
        if (v instanceof Ast.IntVal i) {
            return new BigInteger(i.raw());
        }
        throw new IllegalArgumentException(
            "field " + fieldNameFor(meta, num) + " in " + meta.fullName() +
            " is pxf.BigInt; expected integer literal, got " + v.getClass().getSimpleName());
    }

    private static BigDecimal parseBigDecimal(Ast.Value v, PxfMeta meta, int num) {
        if (v instanceof Ast.FloatVal f) return new BigDecimal(f.raw());
        if (v instanceof Ast.IntVal   i) return new BigDecimal(i.raw());
        throw new IllegalArgumentException(
            "field " + fieldNameFor(meta, num) + " in " + meta.fullName() +
            " requires a numeric literal; got " + v.getClass().getSimpleName());
    }

    /**
     * Encodes a {@code pxf.BigInt} submessage at field {@code num}:
     * {@code abs} (sign-trimmed unsigned big-endian bytes, field 1) plus
     * {@code negative} (bool, field 2). Default-zero for either field is
     * omitted per proto3 — so {@code BigInt(0)} produces an empty payload.
     */
    private static void writeBigInt(CodedOutputStream out, int num, BigInteger value) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        try {
            byte[] absBytes = value.signum() == 0 ? new byte[0] : trimSign(value.abs().toByteArray());
            if (absBytes.length > 0) {
                payloadOut.writeBytes(1, ByteString.copyFrom(absBytes));
            }
            if (value.signum() < 0) {
                payloadOut.writeBool(2, true);
            }
            payloadOut.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
        emitLengthDelimited(out, num, payloadBuf.toByteArray());
    }

    /**
     * Encodes a {@code pxf.Decimal} submessage at field {@code num}:
     * {@code unscaled} (sign-trimmed unsigned big-endian bytes of the
     * unscaled magnitude, field 1), {@code scale} (int32 base-10 power,
     * field 2), and {@code negative} (bool, field 3). Default-zero
     * components omitted.
     */
    private static void writeDecimal(CodedOutputStream out, int num, BigDecimal value) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        try {
            BigInteger unscaledAbs = value.unscaledValue().abs();
            byte[] unscaledBytes = unscaledAbs.signum() == 0 ? new byte[0] : trimSign(unscaledAbs.toByteArray());
            if (unscaledBytes.length > 0) {
                payloadOut.writeBytes(1, ByteString.copyFrom(unscaledBytes));
            }
            if (value.scale() != 0) {
                payloadOut.writeInt32(2, value.scale());
            }
            if (value.signum() < 0) {
                payloadOut.writeBool(3, true);
            }
            payloadOut.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
        emitLengthDelimited(out, num, payloadBuf.toByteArray());
    }

    /**
     * Encodes a {@code pxf.BigFloat} submessage at field {@code num}:
     * {@code mantissa} (sign-trimmed unsigned big-endian, field 1),
     * {@code exponent} (int32 base-10 power, field 2 — actually
     * {@code -BigDecimal.scale()} since the Java port stores BigFloat as a
     * decimal), {@code prec} (uint32 mantissa bit-length, field 3 —
     * defaults to {@code 53} when the mantissa is zero, matching the JVM
     * tier's behavior), and {@code negative} (bool, field 4). The
     * always-emitted {@code prec} field means {@code BigFloat(0)} encodes
     * to {@code 18 35} (prec=53), not an empty payload.
     */
    private static void writeBigFloat(CodedOutputStream out, int num, BigDecimal value) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        try {
            BigInteger mantAbs = value.unscaledValue().abs();
            byte[] mantBytes = mantAbs.signum() == 0 ? new byte[0] : trimSign(mantAbs.toByteArray());
            int prec = mantAbs.bitLength();
            if (prec == 0) {
                prec = 53;
            }
            if (mantBytes.length > 0) {
                payloadOut.writeBytes(1, ByteString.copyFrom(mantBytes));
            }
            if (-value.scale() != 0) {
                payloadOut.writeInt32(2, -value.scale());
            }
            payloadOut.writeUInt32(3, prec);
            if (value.signum() < 0) {
                payloadOut.writeBool(4, true);
            }
            payloadOut.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
        emitLengthDelimited(out, num, payloadBuf.toByteArray());
    }

    /** Strip a leading 0x00 sign byte from {@link BigInteger#toByteArray()} output. */
    private static byte[] trimSign(byte[] b) {
        if (b.length > 1 && b[0] == 0) {
            byte[] out = new byte[b.length - 1];
            System.arraycopy(b, 1, out, 0, out.length);
            return out;
        }
        return b;
    }

    /** Emits the length-delimited record header + payload bytes at field {@code num}. */
    private static void emitLengthDelimited(CodedOutputStream out, int num, byte[] payload) {
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(payload.length);
            out.writeRawBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }
}
