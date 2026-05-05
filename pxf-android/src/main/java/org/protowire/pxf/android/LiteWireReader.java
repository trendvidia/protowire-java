package org.protowire.pxf.android;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Position;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;
import org.protowire.pxf.TimeFormats;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Symmetric to {@link LiteWireWriter}: takes raw protobuf wire bytes plus a
 * {@link PxfMeta}, walks the bytes via {@code protobuf-javalite}'s
 * {@link CodedInputStream}, and produces an {@link Ast.Document} mirroring
 * what would have been parsed from the equivalent PXF text. Pair with
 * {@code Format.formatDocument(...)} (in :pxf-runtime) to get back to PXF
 * text — see {@link LitePxf#marshal(com.google.protobuf.MessageLite, PxfMeta)}
 * for the typed convenience.
 *
 * <p><b>Scope.</b> Handles every protobuf scalar wire type (varint / i32 /
 * i64 / length-delimited including packed-repeated), nested messages
 * (recursive — looked up by FQN through embedded {@link PxfMeta#nestedMetas()}
 * or a caller-supplied {@link PxfRegistry}), enum-typed fields (integers
 * translated back to identifier names via the registry's {@link PxfEnum};
 * bare integers fall through if no enum metadata is registered), and
 * {@code map<K, V>} fields (each wire entry becomes an {@link Ast.MapEntry}
 * inside a {@link Ast.Block}).
 *
 * <p><b>Well-known type fast paths.</b> When {@link PxfMeta#wellKnownKinds()}
 * marks a field as a recognized WKT, the decoder reads the submessage's wire
 * bytes and emits the canonical bare PXF literal — {@link Ast.TimestampVal}
 * for {@code google.protobuf.Timestamp}, {@link Ast.DurationVal} for
 * {@code Duration}, the unwrapped scalar for {@code *Value} wrappers
 * ({@code StringValue}, {@code Int32Value}, etc.), and bare numeric literals
 * for {@code pxf.BigInt} / {@code Decimal} / {@code BigFloat}. Without
 * {@code wellKnownKinds()} populated (hand-built {@code PxfMeta}
 * implementations that omit the table), these fields fall through to the
 * generic nested-message path and decode as {@link Ast.BlockVal}; that's
 * still valid PXF and round-trip-encodes correctly, just not as the canonical
 * bare literal.
 *
 * <p>Output ordering: AST entries appear in <em>field-number order</em>,
 * not declaration order. That's deterministic and matches protobuf's
 * tag-ordered serialization convention.
 */
public final class LiteWireReader {
    private LiteWireReader() {}

    /**
     * Maximum nested-message depth permitted on decode. Mirrors HARDENING.md
     * §Recursion ({@code MaxNestingDepth = 100}) — bounds native call-stack
     * growth on adversarial input. Threaded through {@code readEntries} /
     * {@code readNestedMessage} / {@code readMapEntry} so the limit holds
     * even when nested submessages are decoded through pushLimit/popLimit
     * subreads or fresh sub-streams.
     */
    static final int MAX_NESTING_DEPTH = 100;

    /**
     * Maximum total wire-bytes permitted on a single decode call. Mirrors
     * HARDENING.md ({@code MaxMessageSize = 64 MiB}). Set explicitly via
     * {@link CodedInputStream#setSizeLimit(int)} so the same cap applies
     * regardless of the host's javalite default.
     */
    static final int MAX_MESSAGE_SIZE = 64 * 1024 * 1024;

    // FieldDescriptorProto.Type integers — same constants as LiteWireWriter.
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

    /** Sentinel registry; LiteWireWriter has the same shape. Mirrors message lookups for nested fields. */
    private static final PxfRegistry REGISTRY_REQUIRED = new PxfRegistry() {
        @Override public PxfMeta lookupMessage(String fullName) {
            throw new IllegalStateException(
                "decoding requires a PxfRegistry to resolve nested message '" + fullName +
                "' — call toAst(wire, meta, registry) with one registered");
        }
        @Override public PxfEnum lookupEnum(String fullName) {
            throw new IllegalStateException(
                "decoding requires a PxfRegistry to resolve enum '" + fullName +
                "' — call toAst(wire, meta, registry) with one registered");
        }
    };

    public static Ast.Document toAst(byte[] wire, PxfMeta meta) {
        return toAst(wire, meta, REGISTRY_REQUIRED);
    }

    public static Ast.Document toAst(byte[] wire, PxfMeta meta, PxfRegistry registry) {
        try {
            CodedInputStream in = CodedInputStream.newInstance(wire);
            // HARDENING.md: cap recursion + total message size explicitly so
            // the lite path doesn't rely on javalite's host-default limits.
            in.setRecursionLimit(MAX_NESTING_DEPTH);
            in.setSizeLimit(MAX_MESSAGE_SIZE);
            List<Ast.Entry> entries = readEntries(in, meta, registry, 0);
            return new Ast.Document("", entries, List.of());
        } catch (IOException e) {
            throw new IllegalStateException("CodedInputStream read failed", e);
        }
    }

    /**
     * Walks one message's wire bytes, accumulating per-field-number values
     * (singular / repeated / map), then emits the entries in field-number
     * order.
     */
    private static List<Ast.Entry> readEntries(
            CodedInputStream in, PxfMeta meta, PxfRegistry registry, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException(
                "lite-wire: max nesting depth (" + MAX_NESTING_DEPTH + ") exceeded at " + meta.fullName());
        }
        Map<Integer, String>  fieldNames = invert(meta.fieldNumbers());
        Map<Integer, Integer> fieldKinds = meta.fieldKinds();
        Set<Integer>          repeated   = meta.repeatedFields();
        Set<Integer>          mapFields  = meta.mapFields();

        Map<Integer, Ast.Value>      singles      = new HashMap<>();
        Map<Integer, List<Ast.Value>> repeatedAcc = new HashMap<>();
        Map<Integer, List<Ast.MapEntry>> mapAcc   = new HashMap<>();

        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNum = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);

            String name = fieldNames.get(fieldNum);
            if (name == null) {
                in.skipField(tag);
                continue;
            }
            int kind = fieldKinds.getOrDefault(fieldNum, 0);

            if (mapFields.contains(fieldNum)) {
                Ast.MapEntry entry = readMapEntry(in, fieldNum, meta, registry, depth);
                mapAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>()).add(entry);
                continue;
            }

            if (repeated.contains(fieldNum)) {
                if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED && isPackable(kind)) {
                    // Packed encoding — one length-delimited record holds N values.
                    int len = in.readRawVarint32();
                    int oldLimit = in.pushLimit(len);
                    while (in.getBytesUntilLimit() > 0) {
                        Ast.Value v = readScalarFromPacked(in, kind);
                        repeatedAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>()).add(v);
                    }
                    in.popLimit(oldLimit);
                } else {
                    Ast.Value v = readSingle(in, wireType, kind, fieldNum, meta, registry, depth);
                    repeatedAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>()).add(v);
                }
                continue;
            }

            singles.put(fieldNum, readSingle(in, wireType, kind, fieldNum, meta, registry, depth));
        }

        // Emit in field-number order. Stable + matches protobuf's tag ordering.
        List<Integer> nums = new ArrayList<>();
        nums.addAll(singles.keySet());
        nums.addAll(repeatedAcc.keySet());
        nums.addAll(mapAcc.keySet());
        nums.sort(Comparator.naturalOrder());

        List<Ast.Entry> out = new ArrayList<>(nums.size());
        for (int num : nums) {
            String name = fieldNames.get(num);
            if (name == null) continue;
            if (singles.containsKey(num)) {
                out.add(new Ast.Assignment(Position.UNKNOWN, name, singles.get(num), List.of(), ""));
            } else if (repeatedAcc.containsKey(num)) {
                out.add(new Ast.Assignment(Position.UNKNOWN, name,
                    new Ast.ListVal(Position.UNKNOWN, repeatedAcc.get(num)), List.of(), ""));
            } else if (mapAcc.containsKey(num)) {
                List<Ast.MapEntry> entries = mapAcc.get(num);
                List<Ast.Entry> blockEntries = new ArrayList<>(entries.size());
                blockEntries.addAll(entries);
                out.add(new Ast.Block(Position.UNKNOWN, name, blockEntries, List.of(), ""));
            }
        }
        return out;
    }

    /** Reads a single value (non-packed, non-map) and returns the Ast.Value. */
    private static Ast.Value readSingle(
            CodedInputStream in, int wireType, int kind, int fieldNum,
            PxfMeta meta, PxfRegistry registry, int depth) throws IOException {
        return switch (kind) {
            case K_BOOL                                       -> new Ast.BoolVal(Position.UNKNOWN, in.readBool());
            case K_INT32                                      -> intVal(in.readInt32());
            case K_INT64                                      -> intVal(in.readInt64());
            case K_UINT32                                     -> intVal(Integer.toUnsignedLong(in.readUInt32()));
            case K_UINT64                                     -> uintVal(in.readUInt64());
            case K_SINT32                                     -> intVal(in.readSInt32());
            case K_SINT64                                     -> intVal(in.readSInt64());
            case K_FIXED32                                    -> intVal(Integer.toUnsignedLong(in.readFixed32()));
            case K_FIXED64                                    -> uintVal(in.readFixed64());
            case K_SFIXED32                                   -> intVal(in.readSFixed32());
            case K_SFIXED64                                   -> intVal(in.readSFixed64());
            case K_FLOAT                                      -> floatVal(in.readFloat());
            case K_DOUBLE                                     -> floatVal(in.readDouble());
            case K_STRING                                     -> new Ast.StringVal(Position.UNKNOWN, in.readString());
            case K_BYTES                                      -> new Ast.BytesVal(Position.UNKNOWN, in.readByteArray());
            case K_ENUM                                       -> readEnum(in, fieldNum, meta, registry);
            case K_MESSAGE                                    -> readNestedMessage(in, fieldNum, meta, registry, depth);
            default -> throw new UnsupportedOperationException(
                "unsupported field kind " + kind + " at field " + fieldNum + " in " + meta.fullName());
        };
    }

    /** Reads one packed-repeated element (no tag). */
    private static Ast.Value readScalarFromPacked(CodedInputStream in, int kind) throws IOException {
        return switch (kind) {
            case K_BOOL     -> new Ast.BoolVal(Position.UNKNOWN, in.readBool());
            case K_INT32    -> intVal(in.readInt32());
            case K_INT64    -> intVal(in.readInt64());
            case K_UINT32   -> intVal(Integer.toUnsignedLong(in.readUInt32()));
            case K_UINT64   -> uintVal(in.readUInt64());
            case K_SINT32   -> intVal(in.readSInt32());
            case K_SINT64   -> intVal(in.readSInt64());
            case K_FIXED32  -> intVal(Integer.toUnsignedLong(in.readFixed32()));
            case K_FIXED64  -> uintVal(in.readFixed64());
            case K_SFIXED32 -> intVal(in.readSFixed32());
            case K_SFIXED64 -> intVal(in.readSFixed64());
            case K_FLOAT    -> floatVal(in.readFloat());
            case K_DOUBLE   -> floatVal(in.readDouble());
            default -> throw new UnsupportedOperationException(
                "kind " + kind + " is not packable (string/bytes/message/group can't pack)");
        };
    }

    private static Ast.Value readEnum(
            CodedInputStream in, int fieldNum, PxfMeta meta, PxfRegistry registry) throws IOException {
        int value = in.readEnum();
        PxfEnum em = meta.enumMetas().get(fieldNum);
        if (em == null) {
            String fqn = meta.enumTypes().get(fieldNum);
            if (fqn != null) {
                em = registry.lookupEnum(fqn);
            }
        }
        if (em != null) {
            String name = em.names().get(value);
            if (name != null) {
                return new Ast.IdentVal(Position.UNKNOWN, name);
            }
        }
        // Unknown enum value or no metadata — fall back to integer literal,
        // which the encoder accepts on enum-typed fields per PXF grammar.
        return intVal(value);
    }

    private static Ast.Value readNestedMessage(
            CodedInputStream in, int fieldNum, PxfMeta meta, PxfRegistry registry, int depth) throws IOException {
        // WKT fast path: when the host PxfMeta marks this field as a recognized
        // well-known type, peel off the submessage's length-delimited record
        // and synthesize the canonical bare literal (Timestamp string,
        // unwrapped scalar, etc.) instead of recursing as a generic block.
        int wkt = meta.wellKnownKinds().getOrDefault(fieldNum, PxfMeta.WKT_NONE);
        if (wkt != PxfMeta.WKT_NONE) {
            int len = in.readRawVarint32();
            int oldLimit = in.pushLimit(len);
            Ast.Value v = readWellKnown(in, wkt);
            in.popLimit(oldLimit);
            return v;
        }

        PxfMeta nested = meta.nestedMetas().get(fieldNum);
        if (nested == null) {
            String fqn = meta.messageTypes().get(fieldNum);
            if (fqn != null) {
                nested = registry.lookupMessage(fqn);
            }
            if (nested == null) {
                throw new IllegalArgumentException(
                    "no PxfMeta available for nested message at field " + fieldNum +
                    " of " + meta.fullName());
            }
        }
        int len = in.readRawVarint32();
        int oldLimit = in.pushLimit(len);
        List<Ast.Entry> entries = readEntries(in, nested, registry, depth + 1);
        in.popLimit(oldLimit);
        return new Ast.BlockVal(Position.UNKNOWN, entries);
    }

    /**
     * Reads a recognized well-known submessage's payload bytes (the caller has
     * already pushed the length limit) and returns the canonical bare PXF
     * literal. Re-encoding the returned value via {@link LiteWireWriter}
     * reproduces the same wire bytes — the textual form differs (literal vs.
     * struct), the wire form does not.
     */
    private static Ast.Value readWellKnown(CodedInputStream in, int wkt) throws IOException {
        return switch (wkt) {
            case PxfMeta.WKT_TIMESTAMP    -> readTimestamp(in);
            case PxfMeta.WKT_DURATION     -> readDuration(in);
            case PxfMeta.WKT_STRING_VALUE -> new Ast.StringVal(Position.UNKNOWN, readWrapperString(in));
            case PxfMeta.WKT_BYTES_VALUE  -> new Ast.BytesVal(Position.UNKNOWN, readWrapperBytes(in));
            case PxfMeta.WKT_BOOL_VALUE   -> new Ast.BoolVal(Position.UNKNOWN, readWrapperBool(in));
            case PxfMeta.WKT_INT32_VALUE  -> intVal(readWrapperVarint(in, K_INT32));
            case PxfMeta.WKT_INT64_VALUE  -> intVal(readWrapperVarint(in, K_INT64));
            case PxfMeta.WKT_UINT32_VALUE -> intVal(readWrapperVarint(in, K_UINT32));
            case PxfMeta.WKT_UINT64_VALUE -> uintVal(readWrapperVarint(in, K_UINT64));
            case PxfMeta.WKT_FLOAT_VALUE  -> new Ast.FloatVal(Position.UNKNOWN, Float.toString(readWrapperFloat(in)));
            case PxfMeta.WKT_DOUBLE_VALUE -> new Ast.FloatVal(Position.UNKNOWN, Double.toString(readWrapperDouble(in)));
            case PxfMeta.WKT_BIG_INT      -> readBigInt(in);
            case PxfMeta.WKT_DECIMAL      -> readDecimal(in);
            case PxfMeta.WKT_BIG_FLOAT    -> readBigFloat(in);
            default -> throw new IllegalStateException("unknown WKT kind " + wkt);
        };
    }

    /** Decodes a {@code google.protobuf.Timestamp} payload into an Ast.TimestampVal. */
    private static Ast.TimestampVal readTimestamp(CodedInputStream in) throws IOException {
        long seconds = 0L;
        int nanos = 0;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            int num = WireFormat.getTagFieldNumber(tag);
            switch (num) {
                case 1  -> seconds = in.readInt64();
                case 2  -> nanos   = in.readInt32();
                default -> in.skipField(tag);
            }
        }
        Instant t = Instant.ofEpochSecond(seconds, nanos);
        return new Ast.TimestampVal(Position.UNKNOWN, t, TimeFormats.formatRfc3339(t));
    }

    /** Decodes a {@code google.protobuf.Duration} payload into an Ast.DurationVal. */
    private static Ast.DurationVal readDuration(CodedInputStream in) throws IOException {
        long seconds = 0L;
        int nanos = 0;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            int num = WireFormat.getTagFieldNumber(tag);
            switch (num) {
                case 1  -> seconds = in.readInt64();
                case 2  -> nanos   = in.readInt32();
                default -> in.skipField(tag);
            }
        }
        Duration d = Duration.ofSeconds(seconds, nanos);
        return new Ast.DurationVal(Position.UNKNOWN, d, TimeFormats.formatGoDuration(d));
    }

    /** Reads a {@code StringValue} payload — proto3 default-omitted empty string yields "". */
    private static String readWrapperString(CodedInputStream in) throws IOException {
        String value = "";
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                value = in.readString();
            } else {
                in.skipField(tag);
            }
        }
        return value;
    }

    /** Reads a {@code BytesValue} payload — proto3 default-omitted empty payload yields []. */
    private static byte[] readWrapperBytes(CodedInputStream in) throws IOException {
        byte[] value = new byte[0];
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                value = in.readByteArray();
            } else {
                in.skipField(tag);
            }
        }
        return value;
    }

    /** Reads a {@code BoolValue} payload — proto3 default-omitted empty payload yields false. */
    private static boolean readWrapperBool(CodedInputStream in) throws IOException {
        boolean value = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                value = in.readBool();
            } else {
                in.skipField(tag);
            }
        }
        return value;
    }

    /**
     * Reads an integer-wrapper payload ({@code Int32Value}, {@code UInt64Value},
     * etc.) — peels off the value=1 field if present, returning a long that
     * the caller wraps in either signed ({@link #intVal}) or unsigned
     * ({@link #uintVal}) decimal form. Default-omitted (empty payload) yields 0.
     */
    private static long readWrapperVarint(CodedInputStream in, int kind) throws IOException {
        long value = 0L;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                value = switch (kind) {
                    case K_INT32  -> in.readInt32();
                    case K_INT64  -> in.readInt64();
                    case K_UINT32 -> Integer.toUnsignedLong(in.readUInt32());
                    case K_UINT64 -> in.readUInt64();
                    default -> throw new IllegalStateException("not a varint wrapper kind: " + kind);
                };
            } else {
                in.skipField(tag);
            }
        }
        return value;
    }

    private static float readWrapperFloat(CodedInputStream in) throws IOException {
        float value = 0f;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) value = in.readFloat();
            else in.skipField(tag);
        }
        return value;
    }

    private static double readWrapperDouble(CodedInputStream in) throws IOException {
        double value = 0d;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (WireFormat.getTagFieldNumber(tag) == 1) value = in.readDouble();
            else in.skipField(tag);
        }
        return value;
    }

    /**
     * Decodes a {@code pxf.BigInt} payload — {@code abs} (unsigned big-endian
     * magnitude bytes, field 1) plus {@code negative} (bool, field 2) — into an
     * {@link Ast.IntVal} carrying the BigInteger's exact decimal form. Empty
     * payload (proto3 default-omission applied to both fields) yields 0.
     */
    private static Ast.IntVal readBigInt(CodedInputStream in) throws IOException {
        BigInteger abs = BigInteger.ZERO;
        boolean negative = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1  -> abs      = unsignedBytesToBigInteger(in.readByteArray());
                case 2  -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        BigInteger value = negative ? abs.negate() : abs;
        return new Ast.IntVal(Position.UNKNOWN, value.toString());
    }

    /**
     * Decodes a {@code pxf.Decimal} payload — {@code unscaled} (bytes, field 1),
     * {@code scale} (int32, field 2), {@code negative} (bool, field 3) — into
     * a BigDecimal-shaped {@link Ast.FloatVal}. {@link BigDecimal#toPlainString}
     * avoids scientific notation so the literal lexes as a plain float.
     */
    private static Ast.FloatVal readDecimal(CodedInputStream in) throws IOException {
        BigInteger unscaled = BigInteger.ZERO;
        int scale = 0;
        boolean negative = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1  -> unscaled = unsignedBytesToBigInteger(in.readByteArray());
                case 2  -> scale    = in.readInt32();
                case 3  -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        BigInteger signed = negative ? unscaled.negate() : unscaled;
        BigDecimal value = new BigDecimal(signed, scale);
        return new Ast.FloatVal(Position.UNKNOWN, value.toPlainString());
    }

    /**
     * Decodes a {@code pxf.BigFloat} payload — {@code mantissa} (bytes, field 1),
     * {@code exponent} (int32, field 2), {@code prec} (uint32, field 3 — read
     * but ignored on the way out, since the encoder recomputes it from the
     * mantissa's bitLength), {@code negative} (bool, field 4) — into a
     * {@link Ast.FloatVal}. The value is reconstructed as
     * {@code (-1)^negative × mantissa × 10^exponent}; the encoder stores
     * {@code exponent = -BigDecimal.scale()}, so the decoder mirrors that
     * convention exactly.
     */
    private static Ast.FloatVal readBigFloat(CodedInputStream in) throws IOException {
        BigInteger mantissa = BigInteger.ZERO;
        int exponent = 0;
        boolean negative = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1  -> mantissa = unsignedBytesToBigInteger(in.readByteArray());
                case 2  -> exponent = in.readInt32();
                case 3  -> in.readUInt32(); // prec — encoder recomputes from bitLength
                case 4  -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        BigInteger signed = negative ? mantissa.negate() : mantissa;
        BigDecimal value = new BigDecimal(signed, -exponent);
        return new Ast.FloatVal(Position.UNKNOWN, value.toPlainString());
    }

    /** Treats raw bytes as an unsigned big-endian magnitude. */
    private static BigInteger unsignedBytesToBigInteger(byte[] b) {
        if (b.length == 0) return BigInteger.ZERO;
        return new BigInteger(1, b);
    }

    /**
     * Reads one map entry — a length-delimited submessage with {@code key=1}
     * and {@code value=2} fields. Builds an {@link Ast.MapEntry} whose key
     * is the parser-style stringified form (PXF stores all map keys as
     * strings in the AST regardless of underlying kind).
     */
    private static Ast.MapEntry readMapEntry(
            CodedInputStream in, int mapFieldNum, PxfMeta hostMeta, PxfRegistry registry, int depth) throws IOException {
        PxfMeta entryMeta = hostMeta.nestedMetas().get(mapFieldNum);
        if (entryMeta == null) {
            String fqn = hostMeta.messageTypes().get(mapFieldNum);
            if (fqn != null) {
                entryMeta = registry.lookupMessage(fqn);
            }
            if (entryMeta == null) {
                throw new IllegalArgumentException(
                    "no PxfMeta available for map entry of field " + mapFieldNum +
                    " of " + hostMeta.fullName());
            }
        }
        int len = in.readRawVarint32();
        int oldLimit = in.pushLimit(len);
        // Decode the entry's two fields by walking its sub-bytes via a recursive
        // readEntries call. The result is a list with up to two assignments
        // (key and/or value); proto3 default-omission may drop one or both.
        List<Ast.Entry> entryFields = readEntries(in, entryMeta, registry, depth + 1);
        in.popLimit(oldLimit);

        String  keyStr = "";
        Ast.Value valueVal = new Ast.StringVal(Position.UNKNOWN, "");
        for (Ast.Entry e : entryFields) {
            if (e instanceof Ast.Assignment a) {
                if ("key".equals(a.key())) {
                    keyStr = mapKeyString(a.value());
                } else if ("value".equals(a.key())) {
                    valueVal = a.value();
                }
            }
        }
        return new Ast.MapEntry(Position.UNKNOWN, keyStr, valueVal, List.of(), "");
    }

    /** Coerces an Ast.Value of a primitive map-key kind back to its parser-style string form. */
    private static String mapKeyString(Ast.Value v) {
        if (v instanceof Ast.StringVal s) return s.value();
        if (v instanceof Ast.IntVal i)    return i.raw();
        if (v instanceof Ast.BoolVal b)   return Boolean.toString(b.value());
        throw new IllegalArgumentException("unsupported map key value type: " + v.getClass().getSimpleName());
    }

    private static boolean isPackable(int kind) {
        return switch (kind) {
            case K_BOOL,
                 K_INT32, K_INT64, K_UINT32, K_UINT64,
                 K_SINT32, K_SINT64,
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64,
                 K_FLOAT, K_DOUBLE,
                 K_ENUM -> true;
            default -> false;
        };
    }

    // --- Ast.Value helpers ------------------------------------------------

    private static Ast.IntVal intVal(long value) {
        return new Ast.IntVal(Position.UNKNOWN, Long.toString(value));
    }

    private static Ast.IntVal uintVal(long value) {
        // Unsigned 64-bit — print without sign. Java's Long.toUnsignedString handles this.
        return new Ast.IntVal(Position.UNKNOWN, Long.toUnsignedString(value));
    }

    private static Ast.FloatVal floatVal(double value) {
        return new Ast.FloatVal(Position.UNKNOWN, Double.toString(value));
    }

    private static Ast.FloatVal floatVal(float value) {
        return new Ast.FloatVal(Position.UNKNOWN, Float.toString(value));
    }

    private static <K, V> Map<V, K> invert(Map<K, V> m) {
        Map<V, K> out = new HashMap<>(m.size());
        for (Map.Entry<K, V> e : m.entrySet()) {
            out.put(e.getValue(), e.getKey());
        }
        return out;
    }
}
