// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf.android;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;
import org.protowire.pxf.Ast;
import org.protowire.pxf.BigFloats;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.Limits;
import org.protowire.pxf.Position;
import org.protowire.pxf.PxfException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * inside a {@link Ast.BlockVal}, sorted by key). A field in {@link PxfMeta#keyFields()} comes
 * back in the keyed block form of draft -01 §3.13 whenever every element's key
 * is present, non-empty and distinct, and as a list otherwise.
 *
 * <p><b>Well-known type fast paths.</b> When {@link PxfMeta#wellKnownKinds()}
 * marks a field as a recognized WKT, the decoder reads the submessage's wire
 * bytes and emits the canonical bare PXF literal — {@link Ast.TimestampVal}
 * for {@code google.protobuf.Timestamp}, {@link Ast.DurationVal} for
 * {@code Duration}, the unwrapped scalar for {@code *Value} wrappers
 * ({@code StringValue}, {@code Int32Value}, etc.), and bare numeric literals
 * for {@code pxf.BigInt} / {@code Decimal} / {@code BigFloat}, and a string
 * literal for a {@code pxf.Secret} with no hint or fingerprint. Without
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
        return toAst(wire, meta, registry, DecodeLimits.defaults());
    }

    /** As {@link #toAst(byte[], PxfMeta)} under per-call limits (#79). */
    public static Ast.Document toAst(byte[] wire, PxfMeta meta, DecodeLimits limits) {
        return toAst(wire, meta, REGISTRY_REQUIRED, limits);
    }

    /**
     * As {@link #toAst(byte[], PxfMeta, PxfRegistry)} under per-call limits
     * (HARDENING.md § Mandatory limits, #79): {@code MaxMessageSize} before
     * the first byte is read, {@code MaxNestingDepth} on submessages and
     * map entries, {@code MaxRepeatedCount} on repeated and map elements.
     * The literal limits do not apply to wire input.
     */
    public static Ast.Document toAst(byte[] wire, PxfMeta meta, PxfRegistry registry, DecodeLimits limits) {
        if (wire.length > limits.maxMessageSize()) {
            throw new PxfException(Position.UNKNOWN, Limits.messageSizeError(wire.length, limits.maxMessageSize()));
        }
        try {
            List<Ast.Entry> entries = readEntries(CodedInputStream.newInstance(wire), meta, registry, 0, limits, false);
            return Ast.Document.of("", entries);
        } catch (InvalidProtocolBufferException e) {
            // Malformed input (truncated record, bad varint, invalid UTF-8 in a
            // string field) is a clean rejection, in the same exception type
            // the rest of the tier uses. HARDENING.md item 5.
            throw new PxfException(Position.UNKNOWN, "malformed wire: " + e.getMessage(), e);
        } catch (IOException e) {
            // Unreachable for a byte-array stream; kept so the checked type
            // does not leak.
            throw new IllegalStateException("CodedInputStream read failed", e);
        }
    }

    /**
     * Walks one message's wire bytes, accumulating per-field-number values
     * (singular / repeated / map), then emits the entries in field-number
     * order. A singular field with no presence whose last value is its zero
     * value is left out, as a message read from the same bytes holds nothing
     * there (#180); a map entry ({@code mapEntry}) keeps its key and value.
     */
    private static List<Ast.Entry> readEntries(
            CodedInputStream in, PxfMeta meta, PxfRegistry registry, int depth, DecodeLimits limits,
            boolean mapEntry) throws IOException {
        // HARDENING.md § Recursion: depth is the message's own level, the
        // top-level call being 0 and every nested message or map entry one
        // descent (as in :pb's Pb.unmarshal, protowire#301); it is passed
        // down explicitly because every nested message and map entry is read
        // by a recursive call on the same stream under pushLimit.
        if (depth > limits.maxNestingDepth()) {
            throw new PxfException(Position.UNKNOWN,
                "nesting depth exceeds MaxNestingDepth=" + limits.maxNestingDepth());
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

            // A field whose wire type does not match its declared kind is
            // skipped as an unknown field, as protobuf-go and the full tier
            // do (#160). A repeated packable field legitimately arrives
            // either packed (length-delimited) or one element per record.
            int expected = mapFields.contains(fieldNum) ? WireFormat.WIRETYPE_LENGTH_DELIMITED : wireTypeOf(kind);
            boolean packed = repeated.contains(fieldNum) && isPackable(kind)
                && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;
            if (expected >= 0 && wireType != expected && !packed) {
                in.skipField(tag);
                continue;
            }

            if (mapFields.contains(fieldNum)) {
                Ast.MapEntry entry = readMapEntry(in, fieldNum, meta, registry, depth, limits);
                List<Ast.MapEntry> acc = mapAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>());
                checkRepeated(acc.size(), limits, "map", name);
                acc.add(entry);
                continue;
            }

            if (repeated.contains(fieldNum)) {
                if (packed) {
                    // Packed encoding — one length-delimited record holds N values.
                    int len = in.readRawVarint32();
                    int oldLimit = in.pushLimit(len);
                    while (in.getBytesUntilLimit() > 0) {
                        Ast.Value v = kind == K_ENUM ? readEnum(in, fieldNum, meta, registry) : readScalarFromPacked(in, kind);
                        List<Ast.Value> acc = repeatedAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>());
                        checkRepeated(acc.size(), limits, "repeated", name);
                        acc.add(v);
                    }
                    in.popLimit(oldLimit);
                } else {
                    Ast.Value v = readSingle(in, kind, fieldNum, meta, registry, depth, limits);
                    List<Ast.Value> acc = repeatedAcc.computeIfAbsent(fieldNum, k -> new ArrayList<>());
                    checkRepeated(acc.size(), limits, "repeated", name);
                    acc.add(v);
                }
                continue;
            }

            Ast.Value single = readSingle(in, kind, fieldNum, meta, registry, depth, limits);
            // The last value wins, so a zero after a non-zero clears the field.
            if (!mapEntry && meta.implicitPresenceFields().contains(fieldNum) && isZero(single, fieldNum, meta, registry)) {
                singles.remove(fieldNum);
            } else {
                singles.put(fieldNum, single);
            }
        }

        if (!mapEntry) {
            writeUnsetAnnotated(meta, singles, repeatedAcc, mapAcc, registry, depth, limits);
        }

        // Emit in declaration order, as protowire-go and the full tier write
        // a message (#224); a companion without FIELD_ORDER gives number order.
        List<Integer> nums = new ArrayList<>();
        for (int num : meta.fieldOrder()) {
            if (singles.containsKey(num) || repeatedAcc.containsKey(num) || mapAcc.containsKey(num)) {
                nums.add(num);
            }
        }

        List<Ast.Entry> out = new ArrayList<>(nums.size());
        for (int num : nums) {
            String name = fieldNames.get(num);
            if (name == null) continue;
            if (singles.containsKey(num)) {
                // A message is written `name {`, as the full tier and Go do.
                out.add(singles.get(num) instanceof Ast.BlockVal block
                    ? new Ast.Block(Position.UNKNOWN, name, block.entries(), List.of(), "")
                    : new Ast.Assignment(Position.UNKNOWN, name, singles.get(num), List.of(), ""));
            } else if (repeatedAcc.containsKey(num)) {
                Ast.Block keyed = keyedBlock(name, repeatedAcc.get(num), meta.keyFields().get(num));
                out.add(keyed != null ? keyed : new Ast.Assignment(Position.UNKNOWN, name,
                    new Ast.ListVal(Position.UNKNOWN, repeatedAcc.get(num)), List.of(), ""));
            } else if (mapAcc.containsKey(num)) {
                // Sorted by spelled key and written `name = {`, as the full
                // tier and Go do. Of a key repeated on the wire, the last wins.
                TreeMap<String, Ast.Entry> sorted = new TreeMap<>();
                for (Ast.MapEntry e : mapAcc.get(num)) {
                    sorted.put(e.keyQuoted() ? '"' + Format.escape(e.key()) + '"' : e.key(), e);
                }
                out.add(new Ast.Assignment(Position.UNKNOWN, name,
                    new Ast.BlockVal(Position.UNKNOWN, new ArrayList<>(sorted.values())), List.of(), ""));
            }
        }
        return out;
    }

    /**
     * The keyed block form of a repeated field (draft -01 §3.13), or null when
     * the field is not keyed or the list has no such form: an element with an
     * absent, empty or duplicate key can only be written anonymously. One named
     * block per element, in list order, with the key carried by the name.
     * Mirrors {@code Encoder.encodeList}.
     */
    private static Ast.Block keyedBlock(String field, List<Ast.Value> elements, String keyName) {
        if (keyName == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        List<Ast.Entry> entries = new ArrayList<>(elements.size());
        for (Ast.Value v : elements) {
            if (!(v instanceof Ast.BlockVal element)) {
                return null;
            }
            String key = null;
            List<Ast.Entry> body = new ArrayList<>(element.entries().size());
            for (Ast.Entry e : element.entries()) {
                if (e instanceof Ast.Assignment a && a.key().equals(keyName) && a.value() instanceof Ast.StringVal s) {
                    key = s.value();
                } else {
                    body.add(e);
                }
            }
            if (key == null || key.isEmpty() || !seen.add(key)) {
                return null;
            }
            entries.add(new Ast.Block(Position.UNKNOWN, key, body, List.of(), "",
                Format.needsQuoting(key)));
        }
        return new Ast.Block(Position.UNKNOWN, field, entries, List.of(), "");
    }

    /** Reads a single value (non-packed, non-map) and returns the Ast.Value. */
    /** HARDENING.md MaxRepeatedCount, checked before the (n+1)th element is added. */
    private static void checkRepeated(int size, DecodeLimits limits, String what, String field) {
        if (size >= limits.maxRepeatedCount()) {
            throw new PxfException(Position.UNKNOWN,
                what + " field \"" + field + "\" exceeds MaxRepeatedCount=" + limits.maxRepeatedCount());
        }
    }

    private static Ast.Value readSingle(
            CodedInputStream in, int kind, int fieldNum,
            PxfMeta meta, PxfRegistry registry, int depth, DecodeLimits limits) throws IOException {
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
            case K_STRING                                     -> new Ast.StringVal(Position.UNKNOWN, in.readStringRequireUtf8());
            case K_BYTES                                      -> new Ast.BytesVal(Position.UNKNOWN, in.readByteArray());
            case K_ENUM                                       -> readEnum(in, fieldNum, meta, registry);
            case K_MESSAGE                                    -> readNestedMessage(in, fieldNum, meta, registry, depth, limits);
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
            CodedInputStream in, int fieldNum, PxfMeta meta, PxfRegistry registry, int depth, DecodeLimits limits) throws IOException {
        // WKT fast path: when the host PxfMeta marks this field as a recognized
        // well-known type, peel off the submessage's length-delimited record
        // and synthesize the canonical bare literal (Timestamp string,
        // unwrapped scalar, etc.) instead of recursing as a generic block.
        int wkt = meta.wellKnownKinds().getOrDefault(fieldNum, PxfMeta.WKT_NONE);
        if (wkt != PxfMeta.WKT_NONE) {
            int len = in.readRawVarint32();
            int oldLimit = in.pushLimit(len);
            Ast.Value v = readWellKnown(in, wkt, limits);
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
        List<Ast.Entry> entries = readEntries(in, nested, registry, depth + 1, limits, false);
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
    private static Ast.Value readWellKnown(CodedInputStream in, int wkt, DecodeLimits limits) throws IOException {
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
            case PxfMeta.WKT_FLOAT_VALUE  -> floatVal(readWrapperFloat(in));
            case PxfMeta.WKT_DOUBLE_VALUE -> floatVal(readWrapperDouble(in));
            case PxfMeta.WKT_BIG_INT      -> readBigInt(in);
            case PxfMeta.WKT_DECIMAL      -> readDecimal(in, limits);
            case PxfMeta.WKT_BIG_FLOAT    -> readBigFloat(in, limits);
            case PxfMeta.WKT_SECRET       -> readSecret(in);
            default -> throw new IllegalStateException("unknown WKT kind " + wkt);
        };
    }

    /** Decodes a {@code google.protobuf.Timestamp} payload into an Ast.TimestampVal. */
    private static Ast.TimestampVal readTimestamp(CodedInputStream in) throws IOException {
        long seconds = 0L;
        int nanos = 0;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (wireField(tag, TIME_WIRE)) {
                case 1  -> seconds = in.readInt64();
                case 2  -> nanos   = in.readInt32();
                default -> in.skipField(tag);
            }
        }
        Instant t = Instant.ofEpochSecond(seconds, nanos);
        return new Ast.TimestampVal(Position.UNKNOWN, t, TimeFormats.formatRfc3339(t));
    }

    /**
     * Decodes a {@code google.protobuf.Duration} payload into an Ast.DurationVal,
     * or into a block when the value is too long for a literal.
     */
    private static Ast.Value readDuration(CodedInputStream in) throws IOException {
        long seconds = 0L;
        int nanos = 0;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (wireField(tag, TIME_WIRE)) {
                case 1  -> seconds = in.readInt64();
                case 2  -> nanos   = in.readInt32();
                default -> in.skipField(tag);
            }
        }
        if (!TimeFormats.fitsDurationLiteral(seconds, nanos)) {
            List<Ast.Entry> entries = new ArrayList<>(2);
            entries.add(new Ast.Assignment(Position.UNKNOWN, "seconds", intVal(seconds), List.of(), ""));
            if (nanos != 0) {
                entries.add(new Ast.Assignment(Position.UNKNOWN, "nanos", intVal(nanos), List.of(), ""));
            }
            return new Ast.BlockVal(Position.UNKNOWN, entries);
        }
        Duration d = Duration.ofSeconds(seconds, nanos);
        return new Ast.DurationVal(Position.UNKNOWN, d, TimeFormats.formatGoDuration(d));
    }

    /** Reads a {@code StringValue} payload — proto3 default-omitted empty string yields "". */
    private static String readWrapperString(CodedInputStream in) throws IOException {
        String value = "";
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (wireField(tag, LEN) == 1) {
                value = in.readStringRequireUtf8();
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
            if (wireField(tag, LEN) == 1) {
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
            if (wireField(tag, VARINT) == 1) {
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
            if (wireField(tag, VARINT) == 1) {
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
            if (wireField(tag, I32) == 1) value = in.readFloat();
            else in.skipField(tag);
        }
        return value;
    }

    private static double readWrapperDouble(CodedInputStream in) throws IOException {
        double value = 0d;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            if (wireField(tag, I64) == 1) value = in.readDouble();
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
            switch (wireField(tag, BIG_INT_WIRE)) {
                case 1  -> abs     = unsignedBytesToBigInteger(in.readByteArray());
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
    private static Ast.FloatVal readDecimal(CodedInputStream in, DecodeLimits limits) throws IOException {
        BigInteger unscaled = BigInteger.ZERO;
        int scale = 0;
        boolean negative = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (wireField(tag, DECIMAL_WIRE)) {
                case 1  -> unscaled = unsignedBytesToBigInteger(in.readByteArray());
                case 2  -> scale    = in.readInt32();
                case 3  -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        // Writing the value out materialises |scale| digits, work a five-byte
        // field sets. A scale is a digit count, so the digit cap bounds it
        // (HARDENING.md § Mandatory limits).
        if (Math.abs((long) scale) > limits.maxNumericLiteralDigits()) {
            throw new PxfException(Position.UNKNOWN, "pxf.Decimal scale " + scale
                + " exceeds MaxNumericLiteralDigits=" + limits.maxNumericLiteralDigits());
        }
        // The sign is written from the flag, not from the value, so a negative
        // zero prints -0 or -0.00 as in the full tier's readDecimalStr (#157):
        // a BigDecimal has no negative zero to carry it.
        String magnitude = unscaled.signum() == 0 && scale < 0
            ? "0"
            : new BigDecimal(unscaled, scale).toPlainString();
        return new Ast.FloatVal(Position.UNKNOWN, negative ? "-" + magnitude : magnitude);
    }

    /**
     * Decodes a {@code pxf.BigFloat} payload — {@code mantissa} (bytes, field 1),
     * {@code exponent} (int32, field 2), {@code prec} (uint32, field 3),
     * {@code negative} (bool, field 4) — into its literal, suffix included: the
     * unsigned mantissa × 2^exponent, written as {@code BigFloats} says.
     */
    private static Ast.FloatVal readBigFloat(CodedInputStream in, DecodeLimits limits) throws IOException {
        BigInteger mantissa = BigInteger.ZERO;
        int exponent = 0;
        int prec = 0;
        boolean negative = false;
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (wireField(tag, BIG_FLOAT_WIRE)) {
                case 1  -> mantissa = unsignedBytesToBigInteger(in.readByteArray());
                case 2  -> exponent = in.readInt32();
                case 3  -> prec = in.readUInt32();
                case 4  -> negative = in.readBool();
                default -> in.skipField(tag);
            }
        }
        String literal;
        try {
            // Decoded as a decoder accepts it: a value that rounds past the
            // bound is refused when its literal is written (#186).
            literal = BigFloats.format(mantissa, exponent, prec, negative,
                    limits.maxNumericLiteralDigits(), limits.maxBigFloatExponent(), false);
        } catch (IllegalArgumentException e) {
            throw new PxfException(Position.UNKNOWN, e.getMessage());
        }
        int p = literal.indexOf('p');
        return p < 0
                ? new Ast.FloatVal(Position.UNKNOWN, literal)
                : new Ast.FloatVal(Position.UNKNOWN, literal.substring(0, p), literal.substring(p + 1));
    }

    /**
     * Decodes a {@code pxf.Secret} payload — {@code value}, {@code hint} and
     * {@code fingerprint}, strings at fields 1 to 3. The string literal can
     * hold only {@code value}, so a Secret with either of the others comes
     * back as a block, as in {@code Encoder}.
     */
    private static Ast.Value readSecret(CodedInputStream in) throws IOException {
        String value = "";
        String hint = "";
        String fingerprint = "";
        while (in.getBytesUntilLimit() > 0) {
            int tag = in.readTag();
            switch (wireField(tag, SECRET_WIRE)) {
                case 1  -> value = in.readStringRequireUtf8();
                case 2  -> hint = in.readStringRequireUtf8();
                case 3  -> fingerprint = in.readStringRequireUtf8();
                default -> in.skipField(tag);
            }
        }
        if (hint.isEmpty() && fingerprint.isEmpty()) {
            return new Ast.StringVal(Position.UNKNOWN, value);
        }
        List<Ast.Entry> entries = new ArrayList<>(3);
        addSecretField(entries, "value", value);
        addSecretField(entries, "hint", hint);
        addSecretField(entries, "fingerprint", fingerprint);
        return new Ast.BlockVal(Position.UNKNOWN, entries);
    }

    private static void addSecretField(List<Ast.Entry> entries, String name, String text) {
        if (!text.isEmpty()) {
            entries.add(new Ast.Assignment(Position.UNKNOWN, name, new Ast.StringVal(Position.UNKNOWN, text), List.of(), ""));
        }
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
            CodedInputStream in, int mapFieldNum, PxfMeta hostMeta, PxfRegistry registry, int depth,
            DecodeLimits limits) throws IOException {
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
        List<Ast.Entry> entryFields = readEntries(in, entryMeta, registry, depth + 1, limits, true);
        in.popLimit(oldLimit);

        // An entry lacking its key or its value (left out, or skipped as
        // mistyped) has that field's zero value, as in protobuf-go: 0, false,
        // "", or an empty message, which a well-known type spells as its own
        // zero literal.
        Ast.Value keyVal = null;
        Ast.Value valueVal = null;
        for (Ast.Entry e : entryFields) {
            if (e instanceof Ast.Assignment a) {
                if ("key".equals(a.key())) {
                    keyVal = a.value();
                } else if ("value".equals(a.key())) {
                    valueVal = a.value();
                }
            } else if (e instanceof Ast.Block b && "value".equals(b.name())) {
                valueVal = new Ast.BlockVal(Position.UNKNOWN, b.entries());
            }
        }
        if (keyVal == null) {
            keyVal = zeroValue(entryMeta, 1, registry, depth, limits);
        }
        if (valueVal == null) {
            valueVal = zeroValue(entryMeta, 2, registry, depth, limits);
        }
        // A string key is spelled quoted unless identifier-safe (the
        // marshaller rule, Format.needsQuoting); a bool or integer key is
        // spelled bare.
        String keyStr = mapKeyString(keyVal);
        boolean keyQuoted = keyVal instanceof Ast.StringVal && Format.needsQuoting(keyStr);
        return new Ast.MapEntry(Position.UNKNOWN, keyStr, valueVal, List.of(), "", keyQuoted);
    }

    /**
     * Adds the unset fields a reader that applies (pxf.default) and checks
     * (pxf.required) would not read back as unset (draft -01 § Common Schema
     * Layer; protowire-go#259, #263, #267, #268), as the full tier's Encoder
     * and protowire-go's unsetField decide. One without presence, a plain
     * scalar, list or map, is written as its zero value, {@code []} or
     * {@code {}}. A message-typed one with a default is written {@code null}.
     * A required one with presence cannot be written, since a reader refuses
     * it absent and refuses it null, so it is an error. A oneof member whose
     * sibling is set, and an {@code optional} or oneof scalar with a default,
     * are left out.
     *
     * <p>A scalar has no presence when {@link PxfMeta#implicitPresenceFields()}
     * lists it. A companion from a plugin before that table lists none, so
     * every scalar there has presence: an unset required one is refused and a
     * defaulted one left out, as before the table.
     */
    private static void writeUnsetAnnotated(
            PxfMeta meta,
            Map<Integer, Ast.Value> singles,
            Map<Integer, List<Ast.Value>> repeatedAcc,
            Map<Integer, List<Ast.MapEntry>> mapAcc,
            PxfRegistry registry, int depth, DecodeLimits limits) throws IOException {
        Set<Integer> required = meta.requiredFields();
        Map<Integer, String> defaults = meta.defaults();
        if (required.isEmpty() && defaults.isEmpty()) {
            return;
        }
        Set<Integer> implicit = meta.implicitPresenceFields();
        Map<Integer, String> oneofOf = meta.oneofOf();
        Set<String> chosen = new HashSet<>(); // the oneofs a set member chose
        for (int n : singles.keySet()) {
            if (oneofOf.containsKey(n)) chosen.add(oneofOf.get(n));
        }
        for (Map.Entry<String, Integer> f : meta.fieldNumbers().entrySet()) {
            int num = f.getValue();
            boolean req = required.contains(num);
            if ((!req && !defaults.containsKey(num))
                    || singles.containsKey(num) || repeatedAcc.containsKey(num) || mapAcc.containsKey(num)) {
                continue;
            }
            if (meta.mapFields().contains(num)) {
                mapAcc.put(num, new ArrayList<>());
                continue;
            }
            if (meta.repeatedFields().contains(num)) {
                repeatedAcc.put(num, new ArrayList<>());
                continue;
            }
            int kind = meta.fieldKinds().getOrDefault(num, 0);
            boolean message = kind == K_MESSAGE;
            boolean presence = message || !implicit.contains(num);
            if (!presence) {
                singles.put(num, zeroValue(meta, num, registry, depth, limits));
            } else if (req) {
                throw new PxfException(Position.UNKNOWN,
                    "pxf: " + meta.fullName() + "." + f.getKey() + " is unset, and the field is (pxf.required)");
            } else if (message && !chosen.contains(oneofOf.get(num))) {
                singles.put(num, new Ast.NullVal(Position.UNKNOWN));
            }
        }
    }

    /**
     * The zero value of a map entry's field, read the way the wire would
     * give it: a zero varint, zero fixed bytes, or an empty record. Reading
     * it, rather than spelling it here, keeps an enum's zero name and a
     * well-known type's zero literal in step with the reader.
     */
    private static Ast.Value zeroValue(
            PxfMeta entryMeta, int fieldNum, PxfRegistry registry, int depth, DecodeLimits limits) throws IOException {
        int kind = entryMeta.fieldKinds().getOrDefault(fieldNum, K_STRING);
        byte[] zero = switch (wireTypeOf(kind)) {
            case WireFormat.WIRETYPE_FIXED32 -> new byte[4];
            case WireFormat.WIRETYPE_FIXED64 -> new byte[8];
            default                          -> new byte[1];  // a zero varint, or a zero length
        };
        return readSingle(CodedInputStream.newInstance(zero), kind, fieldNum, entryMeta, registry, depth + 1, limits);
    }

    /** Coerces an Ast.Value of a primitive map-key kind back to its parser-style string form. */
    private static String mapKeyString(Ast.Value v) {
        if (v instanceof Ast.StringVal s) return s.value();
        if (v instanceof Ast.IntVal i)    return i.raw();
        if (v instanceof Ast.BoolVal b)   return Boolean.toString(b.value());
        throw new IllegalArgumentException("unsupported map key value type: " + v.getClass().getSimpleName());
    }

    /**
     * The wire type a field of this kind is written with, or -1 for a kind
     * this reader does not handle (left for {@link #readSingle} to reject).
     */
    private static int wireTypeOf(int kind) {
        return switch (kind) {
            case K_BOOL, K_INT32, K_INT64, K_UINT32, K_UINT64,
                 K_SINT32, K_SINT64, K_ENUM          -> WireFormat.WIRETYPE_VARINT;
            case K_FIXED64, K_SFIXED64, K_DOUBLE     -> WireFormat.WIRETYPE_FIXED64;
            case K_FIXED32, K_SFIXED32, K_FLOAT      -> WireFormat.WIRETYPE_FIXED32;
            case K_STRING, K_BYTES, K_MESSAGE        -> WireFormat.WIRETYPE_LENGTH_DELIMITED;
            default                                  -> -1;
        };
    }

    // The wire type of each field of a well-known payload, field 1 first.
    private static final int VARINT = WireFormat.WIRETYPE_VARINT;
    private static final int I64    = WireFormat.WIRETYPE_FIXED64;
    private static final int I32    = WireFormat.WIRETYPE_FIXED32;
    private static final int LEN    = WireFormat.WIRETYPE_LENGTH_DELIMITED;
    private static final int[] TIME_WIRE      = {VARINT, VARINT};          // seconds, nanos
    private static final int[] BIG_INT_WIRE   = {LEN, VARINT};             // abs, negative
    private static final int[] DECIMAL_WIRE   = {LEN, VARINT, VARINT};     // unscaled, scale, negative
    private static final int[] BIG_FLOAT_WIRE = {LEN, VARINT, VARINT, VARINT}; // mantissa, exponent, prec, negative
    private static final int[] SECRET_WIRE    = {LEN, LEN, LEN};           // value, hint, fingerprint

    /**
     * The field number of a well-known payload's tag when its wire type is
     * the one {@code wireTypes} declares for that field, and 0 otherwise, so a
     * mistyped or unknown field falls to the caller's skip (#160).
     */
    private static int wireField(int tag, int... wireTypes) {
        int num = WireFormat.getTagFieldNumber(tag);
        return num >= 1 && num <= wireTypes.length
            && WireFormat.getTagWireType(tag) == wireTypes[num - 1] ? num : 0;
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

    /**
     * Whether a value read for a field with no presence is that field's zero
     * value: 0, false, "", empty bytes, enum 0 or +0.0. A -0.0 has a bit set,
     * so protobuf keeps it, and so does this. Only scalars and enums reach
     * here; a message field always has presence.
     */
    private static boolean isZero(Ast.Value v, int fieldNum, PxfMeta meta, PxfRegistry registry) {
        return switch (v) {
            case Ast.IntVal i -> i.raw().equals("0");
            case Ast.BoolVal b -> !b.value();
            case Ast.StringVal str -> str.value().isEmpty();
            case Ast.BytesVal b -> b.value().length == 0;
            case Ast.FloatVal f -> f.raw().equals("0");
            case Ast.IdentVal id -> {
                PxfEnum em = meta.enumMetas().get(fieldNum);
                String fqn = meta.enumTypes().get(fieldNum);
                if (em == null && fqn != null) em = registry.lookupEnum(fqn);
                Integer n = em == null ? null : em.values().get(id.name());
                yield n != null && n == 0;
            }
            default -> false;
        };
    }

    private static Ast.FloatVal floatVal(double value) {
        return new Ast.FloatVal(Position.UNKNOWN, Format.formatDouble(value));
    }

    private static Ast.FloatVal floatVal(float value) {
        return new Ast.FloatVal(Position.UNKNOWN, Format.formatFloat(value));
    }

    private static <K, V> Map<V, K> invert(Map<K, V> m) {
        Map<V, K> out = new HashMap<>(m.size());
        for (Map.Entry<K, V> e : m.entrySet()) {
            out.put(e.getValue(), e.getKey());
        }
        return out;
    }
}
