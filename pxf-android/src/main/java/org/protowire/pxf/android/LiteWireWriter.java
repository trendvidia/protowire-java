package org.protowire.pxf.android;

import com.google.protobuf.CodedOutputStream;
import org.protowire.pxf.Ast;
import org.protowire.pxf.PxfMeta;

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
 * <p><b>Scope.</b> This first cut handles every protobuf scalar
 * type ({@code int32/64}, {@code uint32/64}, {@code sint32/64},
 * {@code fixed32/64}, {@code sfixed32/64}, {@code bool}, {@code float},
 * {@code double}, {@code string}, {@code bytes}) and {@code repeated}
 * scalars with the proto3 default-packing rules. Not yet supported (queued
 * follow-ups): nested messages, enums, maps, oneofs, well-known wrappers,
 * applying {@code (pxf.default)}, and validating {@code (pxf.required)}.
 *
 * <p>Encountering a field of an unsupported kind throws
 * {@link UnsupportedOperationException}; encountering an unknown field name
 * throws {@link IllegalArgumentException}. Both cases are deliberate: a
 * future PR will narrow the gaps as the corresponding emitter logic lands.
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
    private static final int K_BYTES    = 12;
    private static final int K_UINT32   = 13;
    private static final int K_SFIXED32 = 15;
    private static final int K_SFIXED64 = 16;
    private static final int K_SINT32   = 17;
    private static final int K_SINT64   = 18;

    /** Encode a top-level PXF document into protobuf wire bytes. */
    public static byte[] encode(Ast.Document doc, PxfMeta meta) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buffer);
        writeEntries(out, doc.entries(), meta);
        try {
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream flush failed", e);
        }
        return buffer.toByteArray();
    }

    private static void writeEntries(CodedOutputStream out, List<Ast.Entry> entries, PxfMeta meta) {
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
                        writeRepeated(out, num, kind, list, packed.contains(num));
                    } else if (repeated.contains(num)) {
                        // Single value into a repeated slot — protobuf permits this; emit one element.
                        writeScalar(out, num, kind, a.value());
                    } else {
                        writeScalar(out, num, kind, a.value());
                    }
                }
                case Ast.Block b -> throw new UnsupportedOperationException(
                    "nested message field '" + b.name() + "' is not yet supported by the lite-runtime encoder");
                case Ast.MapEntry m -> throw new UnsupportedOperationException(
                    "map entry '" + m.key() + "' is not yet supported by the lite-runtime encoder");
            }
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

    private static void writeRepeated(CodedOutputStream out, int num, int kind, Ast.ListVal list, boolean packed) {
        if (!packed) {
            for (Ast.Value v : list.elements()) {
                writeScalar(out, num, kind, v);
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
