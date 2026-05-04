package org.protowire.pxf.android;

import com.google.protobuf.CodedOutputStream;
import org.protowire.pxf.Ast;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Not yet supported (queued follow-ups): maps, oneof mutual-exclusion
 * checks, well-known wrappers, applying {@code (pxf.default)}, and
 * validating {@code (pxf.required)}.
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
        writeEntries(out, doc.entries(), meta, registry);
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
            PxfRegistry registry) {
        Map<String, Integer> fieldNumbers = meta.fieldNumbers();
        Map<Integer, Integer> fieldKinds = meta.fieldKinds();
        Set<Integer> repeated = meta.repeatedFields();
        Set<Integer> packed = meta.packedFields();

        for (Ast.Entry entry : entries) {
            switch (entry) {
                case Ast.Assignment a -> {
                    Integer num = fieldNumbers.get(a.key());
                    if (num == null) {
                        throw new IllegalArgumentException("unknown field name: " + a.key());
                    }
                    int kind = fieldKinds.getOrDefault(num, 0);
                    if (repeated.contains(num) && a.value() instanceof Ast.ListVal list) {
                        writeRepeated(out, num, kind, list, packed.contains(num), meta, registry);
                    } else {
                        writeField(out, num, kind, a.value(), meta, registry);
                    }
                }
                case Ast.Block b -> {
                    Integer num = fieldNumbers.get(b.name());
                    if (num == null) {
                        throw new IllegalArgumentException("unknown field name: " + b.name());
                    }
                    int kind = fieldKinds.getOrDefault(num, 0);
                    if (kind != K_MESSAGE) {
                        throw new IllegalArgumentException(
                            "block syntax requires a message-typed field; '" + b.name() +
                            "' has kind " + kind);
                    }
                    writeNestedMessage(out, num, b.entries(), meta, registry);
                }
                case Ast.MapEntry m -> throw new UnsupportedOperationException(
                    "map entry '" + m.key() + "' is not yet supported by the lite-runtime encoder");
            }
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
}
