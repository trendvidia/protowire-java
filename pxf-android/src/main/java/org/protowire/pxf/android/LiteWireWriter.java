// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf.android;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.protowire.pxf.Ast;
import org.protowire.pxf.BigFloats;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.Limits;
import org.protowire.pxf.Parser;
import org.protowire.pxf.Position;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.Token;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;
import org.protowire.pxf.RowBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * ({@code pxf.BigInt}, {@code pxf.Decimal}, {@code pxf.BigFloat}), and
 * {@code pxf.Secret}'s string literal. WKT
 * dispatch is driven by {@link PxfMeta#wellKnownKinds()} — the codegen
 * plugin populates that table at build time, and {@link LiteWireReader}
 * uses the same constants for decoder fast paths, so the two directions
 * stay in lockstep.
 *
 * <p>A field in {@link PxfMeta#keyFields()} also takes the keyed block form
 * of draft -01 §3.13, with entry names supplying each element's key field.
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
        return encode(doc, meta, registry, DecodeLimits.defaults());
    }

    /** As {@link #encode(Ast.Document, PxfMeta)} under per-call limits. */
    public static byte[] encode(Ast.Document doc, PxfMeta meta, DecodeLimits limits) {
        return encode(doc, meta, REGISTRY_REQUIRED, limits);
    }

    /**
     * As {@link #encode(Ast.Document, PxfMeta, PxfRegistry)} under per-call
     * limits (HARDENING.md § Mandatory limits): {@code MaxRepeatedCount} on a
     * repeated, keyed or map field across one message body,
     * {@code MaxNumericLiteralDigits} on the big-number types and
     * {@code MaxBigFloatExponent} on a {@code pxf.BigFloat}. The literal and
     * nesting limits are the {@link Parser}'s.
     */
    public static byte[] encode(Ast.Document doc, PxfMeta meta, PxfRegistry registry, DecodeLimits limits) {
        try {
            return encode(doc.entries(), meta, registry, limits, false);
        } catch (RequiredAbsent e) {
            // protowire-go checks required fields after the document, so it
            // reports them at its start, by the path to the field.
            throw new PxfException(Position.UNKNOWN, "required field " + Token.quote(e.path) + " is absent");
        }
    }

    // -- @dataset rows (#212) ------------------------------------------------

    /**
     * As {@link #encodeRows(Ast.DatasetDirective, PxfMeta, PxfRegistry, DecodeLimits)}
     * for a row type with no nested-message or enum field outside its
     * generated companion's own references.
     */
    public static Iterator<byte[]> encodeRows(Ast.DatasetDirective dataset, PxfMeta meta) {
        return encodeRows(dataset, meta, REGISTRY_REQUIRED, DecodeLimits.defaults());
    }

    /** As {@link #encodeRows(Ast.DatasetDirective, PxfMeta, PxfRegistry, DecodeLimits)} under the default limits. */
    public static Iterator<byte[]> encodeRows(Ast.DatasetDirective dataset, PxfMeta meta, PxfRegistry registry) {
        return encodeRows(dataset, meta, registry, DecodeLimits.defaults());
    }

    /**
     * Each row of {@code dataset} as the PB bytes of its message, whose type
     * {@code meta} describes (draft §3.4.4). Parse them with the row type's
     * generated {@code parseFrom}. Each row binds as
     * {@link #encodeRow(List, Ast.DatasetRow, PxfMeta, PxfRegistry, DecodeLimits)}
     * binds it, when the iterator reaches it, so a row the type refuses fails
     * that {@code next()} call with a {@link PxfException} at the row. This
     * is the lite tier's counterpart of the full tier's {@code BindRow} and
     * protowire-go's {@code BindRow}, with the same verdicts and error texts.
     *
     * <p>{@code meta} is the companion of the type the dataset's header
     * names ({@link Ast.DatasetDirective#type()}). For a document, bind
     * each of {@link Ast.Document#datasets()} with its own type's companion.
     */
    public static Iterator<byte[]> encodeRows(Ast.DatasetDirective dataset, PxfMeta meta, PxfRegistry registry,
            DecodeLimits limits) {
        List<String> columns = dataset.columns();
        Iterator<Ast.DatasetRow> rows = dataset.rows().iterator();
        return new Iterator<>() {
            private boolean checked;

            @Override public boolean hasNext() {
                return rows.hasNext();
            }

            @Override public byte[] next() {
                Ast.DatasetRow row = rows.next();
                // The column paths depend on the type and the header, never
                // on the row, so they are checked once, at the first row.
                if (!checked) {
                    checkColumnPaths(meta, columns, registry, row.pos());
                    checked = true;
                }
                return bindRow(columns, row, meta, registry, limits);
            }
        };
    }

    /**
     * As {@link #encodeRow(List, Ast.DatasetRow, PxfMeta, PxfRegistry, DecodeLimits)}
     * for a row type with no nested-message or enum field outside its
     * generated companion's own references.
     */
    public static byte[] encodeRow(List<String> columns, Ast.DatasetRow row, PxfMeta meta) {
        return encodeRow(columns, row, meta, REGISTRY_REQUIRED, DecodeLimits.defaults());
    }

    /** As {@link #encodeRow(List, Ast.DatasetRow, PxfMeta, PxfRegistry, DecodeLimits)} under the default limits. */
    public static byte[] encodeRow(List<String> columns, Ast.DatasetRow row, PxfMeta meta, PxfRegistry registry) {
        return encodeRow(columns, row, meta, registry, DecodeLimits.defaults());
    }

    /**
     * One {@code @dataset} row as the PB bytes of its message, whose type
     * {@code meta} describes, its cells bound to {@code columns} in order.
     *
     * <p>A non-empty cell in column {@code c} binds as the body entry
     * {@code c = <value>} would: a list or block cell is a list or message,
     * {@code {}} a present empty message, and {@code null} follows a body's
     * null rules. A dotted column {@code a.b} binds into the sub-message
     * {@code a}, and every cell under one prefix lands in one block. An empty
     * cell leaves its field absent, so {@code (pxf.default)} and
     * {@code (pxf.required)} apply ({@link org.protowire.pxf.RowBody}).
     *
     * <p>Every column must name a field, and a dotted one a path through
     * singular message fields whose types are not written as literals; a
     * broken column fails every row, its cells empty or not. An error is
     * reported at the row, naming the column it fell in, as the full tier's
     * {@code BindRow} reports it. {@code limits} apply as in
     * {@link #encode(Ast.Document, PxfMeta, PxfRegistry, DecodeLimits)},
     * except {@code MaxMessageSize}: the row's body repeats the column names,
     * so it is wider than the row, and is read at the default.
     *
     * @throws IllegalArgumentException if the row's cells and the columns differ in number
     */
    public static byte[] encodeRow(List<String> columns, Ast.DatasetRow row, PxfMeta meta, PxfRegistry registry,
            DecodeLimits limits) {
        checkColumnPaths(meta, columns, registry, row.pos());
        return bindRow(columns, row, meta, registry, limits);
    }

    private static byte[] bindRow(List<String> columns, Ast.DatasetRow row, PxfMeta meta, PxfRegistry registry,
            DecodeLimits limits) {
        RowBody body = RowBody.of(columns, row);
        try {
            Ast.Document doc = Parser.parse(body.text(),
                limits.withMaxMessageSize(DecodeLimits.defaults().maxMessageSize()));
            return encode(doc, meta, registry, limits);
        } catch (PxfException e) {
            throw body.atRow(e);
        }
    }

    /** The well-known kinds written as a literal, which a column path cannot go into, by kind. */
    private static final Map<Integer, String> LITERAL_TYPES = Map.ofEntries(
        Map.entry(PxfMeta.WKT_TIMESTAMP, "google.protobuf.Timestamp"),
        Map.entry(PxfMeta.WKT_DURATION, "google.protobuf.Duration"),
        Map.entry(PxfMeta.WKT_STRING_VALUE, "google.protobuf.StringValue"),
        Map.entry(PxfMeta.WKT_BYTES_VALUE, "google.protobuf.BytesValue"),
        Map.entry(PxfMeta.WKT_BOOL_VALUE, "google.protobuf.BoolValue"),
        Map.entry(PxfMeta.WKT_INT32_VALUE, "google.protobuf.Int32Value"),
        Map.entry(PxfMeta.WKT_INT64_VALUE, "google.protobuf.Int64Value"),
        Map.entry(PxfMeta.WKT_UINT32_VALUE, "google.protobuf.UInt32Value"),
        Map.entry(PxfMeta.WKT_UINT64_VALUE, "google.protobuf.UInt64Value"),
        Map.entry(PxfMeta.WKT_FLOAT_VALUE, "google.protobuf.FloatValue"),
        Map.entry(PxfMeta.WKT_DOUBLE_VALUE, "google.protobuf.DoubleValue"),
        Map.entry(PxfMeta.WKT_BIG_INT, "pxf.BigInt"),
        Map.entry(PxfMeta.WKT_DECIMAL, "pxf.Decimal"),
        Map.entry(PxfMeta.WKT_BIG_FLOAT, "pxf.BigFloat"));

    /**
     * Checks {@code columns} against the row type (draft §3.4.4): each
     * segment names a field, and each segment but the last a singular message
     * field whose type is not written as a literal. A path has no index, so
     * it cannot go through a repeated or map field. A {@code pxf.Secret} has
     * a block form as well as its literal, so a path may go into one. The
     * full tier's {@code BindRow.checkColumnPaths} and protowire-go's
     * {@code checkColumnPaths}, with their texts.
     */
    private static void checkColumnPaths(PxfMeta root, List<String> columns, PxfRegistry registry, Position pos) {
        for (String c : columns) {
            PxfMeta md = root;
            int from = 0;
            while (true) {
                int dot = c.indexOf('.', from);
                String seg = dot < 0 ? c.substring(from) : c.substring(from, dot);
                Integer num = md.fieldNumbers().get(seg);
                if (num == null) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": " + md.fullName() + " has no field \"" + seg + "\"");
                }
                if (dot < 0) break;
                String at = c.substring(0, dot);
                if (md.mapFields().contains(num)) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at
                        + "\" is a map field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)");
                }
                if (md.repeatedFields().contains(num)) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at
                        + "\" is a repeated field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)");
                }
                if (md.fieldKinds().getOrDefault(num, 0) != K_MESSAGE) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at + "\" is not a message field");
                }
                String literal = LITERAL_TYPES.get(md.wellKnownKinds().getOrDefault(num, PxfMeta.WKT_NONE));
                if (literal != null) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at + "\" is a " + literal
                        + ", which is written as a literal; a column path cannot go into it (draft §3.4.4)");
                }
                md = nestedMetaOf(num, md, registry);
                from = dot + 1;
            }
        }
    }

    private static final String ANY = "google.protobuf.Any";

    /**
     * An Any block that opens with a type entry (draft -01 "Any Blocks"): the
     * rest of the block is a body of the type the URL names, written into
     * {@code value}, and the URL goes into {@code type_url} as written. The
     * URL's last segment names the type, which {@code registry} resolves, as
     * the full tier's TypeResolver and protowire-go's do. An Any block
     * without a type entry is written with its own two fields.
     */
    private static byte[] encodeAny(Ast.TypeEntry t, List<Ast.Entry> body, PxfRegistry registry, DecodeLimits limits) {
        String url = t.url();
        // At the URL, where protowire-go reports these two (#222).
        if (registry == REGISTRY_REQUIRED) {
            throw new PxfException(t.urlPos(), "cannot resolve Any type " + Token.quote(url)
                + ": no PxfRegistry (LiteWireWriter.encode(doc, meta, registry))");
        }
        PxfMeta inner = registry.lookupMessage(url.substring(url.lastIndexOf('/') + 1));
        if (inner == null) {
            throw new PxfException(t.urlPos(), "cannot resolve Any type " + Token.quote(url) + ": not found");
        }
        byte[] value = encode(body, inner, registry, limits, false);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buf);
        try {
            // proto3 fields without presence: an empty one is not written.
            if (!url.isEmpty()) {
                out.writeString(1, url);
            }
            if (value.length > 0) {
                out.writeByteArray(2, value);
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
        return buf.toByteArray();
    }

    /**
     * A {@code (pxf.required)} field a body left unset, on its way out of the
     * nested bodies: each one it leaves puts its own path segment in front,
     * as {@code in.} or {@code items[0].}, so the message names the field as
     * protowire-go does. It never leaves {@link #encode}.
     */
    private static final class RequiredAbsent extends RuntimeException {
        final String path;

        RequiredAbsent(String path) {
            super(path, null, false, false);
            this.path = path;
        }

        RequiredAbsent under(String segment) {
            return new RequiredAbsent(segment + "." + path);
        }
    }

    /**
     * Writes one message body. Its fields come out in field-number order,
     * whatever order the document binds them in, and a field with no
     * presence that holds its zero value is left out: both as protowire-go
     * and the full tier write it (#156). A map entry is the exception to the
     * second rule ({@code mapEntry}): its key and value are always written,
     * as protobuf writes them.
     */
    private static byte[] encode(
            List<Ast.Entry> entries, PxfMeta meta, PxfRegistry registry, DecodeLimits limits, boolean mapEntry) {
        if (!entries.isEmpty() && entries.get(0) instanceof Ast.TypeEntry t && ANY.equals(meta.fullName())) {
            return encodeAny(t, entries.subList(1, entries.size()), registry, limits);
        }
        Body body = new Body();
        // Track which field numbers were set in the input + which oneof slots
        // got claimed; needed for default application, oneof mutual-exclusion
        // checks, and required-field validation.
        Set<Integer> setFields = new HashSet<>();
        Map<String, Integer> oneofSet = new HashMap<>();
        Map<Integer, Set<String>> mapKeys = new HashMap<>();
        writeEntries(body, entries, meta, registry, limits, setFields, oneofSet, mapKeys, mapEntry);
        applyDefaults(body, meta, registry, limits, setFields, oneofSet, mapEntry);
        validateRequired(meta, setFields);
        return body.bytes();
    }

    /**
     * A message body being written, with where each field's bytes end, so
     * that fields bound out of field-number order can be put back in it.
     * Protobuf says a serializer should write known fields in that order,
     * and Go does; the bytes of one field keep their document order. A body
     * already in order, the common case, is returned as written.
     */
    private static final class Body {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final CodedOutputStream out = CodedOutputStream.newInstance(buffer);
        private int[] nums = new int[8];
        private int[] ends = new int[8];
        private int count;
        private boolean sorted = true;

        /** Records that the bytes written since the last mark belong to field {@code num}. */
        void mark(int num) {
            flush();
            if (count == nums.length) {
                nums = java.util.Arrays.copyOf(nums, count * 2);
                ends = java.util.Arrays.copyOf(ends, count * 2);
            }
            if (count > 0 && num < nums[count - 1]) {
                sorted = false;
            }
            nums[count] = num;
            ends[count] = buffer.size();
            count++;
        }

        byte[] bytes() {
            flush();
            byte[] written = buffer.toByteArray();
            if (sorted) {
                return written;
            }
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            // A stable sort, so one field's spans keep their order.
            java.util.Arrays.sort(order, (x, y) -> Integer.compare(nums[x], nums[y]));
            byte[] result = new byte[written.length];
            int at = 0;
            for (int i : order) {
                int start = i == 0 ? 0 : ends[i - 1];
                System.arraycopy(written, start, result, at, ends[i] - start);
                at += ends[i] - start;
            }
            return result;
        }

        private void flush() {
            try {
                out.flush();
            } catch (IOException e) {
                throw new IllegalStateException("CodedOutputStream flush failed", e);
            }
        }
    }

    /**
     * Whether binding {@code v} to field {@code num} writes nothing: the field
     * has no presence ({@link PxfMeta#implicitPresenceFields()}) and {@code v}
     * is its zero value. A {@code PxfMeta} without the table writes every zero.
     */
    private static boolean writesNothing(int num, int kind, Ast.Value v, PxfMeta meta, PxfRegistry registry) {
        if (kind == K_MESSAGE || !meta.implicitPresenceFields().contains(num)) {
            return false;
        }
        if (kind == K_ENUM) {
            return enumNumber(num, v, meta, registry) == 0;
        }
        rejectPrecision(v, fieldNameFor(meta, num));
        checkScalar(kind, v, fieldNameFor(meta, num));
        return isDefaultScalar(kind, v);
    }

    private static void writeEntries(
            Body body,
            List<Ast.Entry> entries,
            PxfMeta meta,
            PxfRegistry registry,
            DecodeLimits limits,
            Set<Integer> setFields,
            Map<String, Integer> oneofSet,
            Map<Integer, Set<String>> mapKeys,
            boolean mapEntry) {
        CodedOutputStream out = body.out;
        Map<String, Integer> fieldNumbers = meta.fieldNumbers();
        Map<Integer, Integer> fieldKinds = meta.fieldKinds();
        Set<Integer> repeated = meta.repeatedFields();
        Set<Integer> packed = meta.packedFields();
        Map<Integer, String> oneofOf = meta.oneofOf();
        Map<Integer, Integer> counts = new HashMap<>();

        for (Ast.Entry entry : entries) {
            switch (entry) {
                case Ast.Assignment a -> {
                    if (a.keyQuoted()) {
                        // Grammar accepts a string at entry-name position
                        // everywhere; the schema layer restricts it to keyed
                        // repeated fields' blocks (draft -01 §3.13), whose
                        // entries writeKeyedBlock reads.
                        throw new PxfException(a.pos(), "quoted entry name \"" + a.key()
                            + "\" is only valid inside a keyed repeated field's block (draft -01 §3.13)");
                    }
                    Integer num = fieldNumbers.get(a.key());
                    if (num == null) {
                        throw new PxfException(a.pos(), "unknown field " + Token.quote(a.key()) + " in " + meta.fullName());
                    }
                    checkOneofCollision(num, a.key(), a.pos(), meta, oneofOf, oneofSet);
                    checkBoundOnce(num, a.key(), a.pos(), meta, setFields);
                    int kind = fieldKinds.getOrDefault(num, 0);
                    // PXF `field = null`: present-but-null, so it keeps
                    // (pxf.default) off, but no wire bytes are emitted. This
                    // is the canonical way to "explicitly absent" a wrapper
                    // or proto3 optional field in PXF. A required field is
                    // not nullable, so checkNullable rejects it there.
                    if (a.value() instanceof Ast.NullVal) {
                        checkNullable(num, a.key(), a.value().pos(), meta);
                        recordSet(num, oneofOf, setFields, oneofSet);
                        break;
                    }
                    String keyName = meta.keyFields().get(num);
                    if (keyName != null && a.value() instanceof Ast.BlockVal kbv) {
                        checkCount(num, kbv.entries().size(), a.key(), a.pos(), counts, limits);
                        writeKeyedBlock(out, num, kbv.entries(), keyName, meta, registry, limits);
                    } else if (repeated.contains(num) && a.value() instanceof Ast.ListVal list) {
                        if (keyName != null) {
                            checkAnonymousKeys(list, keyName, a.key());
                        }
                        checkCount(num, list.elements().size(), a.key(), a.pos(), counts, limits);
                        writeRepeated(out, num, kind, list, packed.contains(num), meta, registry, limits);
                    } else if (meta.mapFields().contains(num) && a.value() instanceof Ast.BlockVal mbv) {
                        // PXF accepts both `labels { … }` (bare-block) and
                        // `labels = { … }` (assignment-with-block) for map
                        // fields; the bare-block form lands as Ast.Block in
                        // writeEntries above, the assignment form lands here
                        // as Ast.Assignment(BlockVal). Route both through the
                        // map encoder.
                        checkCount(num, mbv.entries().size(), a.key(), a.pos(), counts, limits);
                        writeMap(out, num, mbv.entries(), meta, registry, limits,
                            mapKeys.computeIfAbsent(num, k -> new HashSet<>()));
                    } else if (repeated.contains(num) && !meta.mapFields().contains(num)) {
                        // A repeated field is written with a list (draft -01
                        // § Entries and Keys): a scalar, or a block outside
                        // the keyed form above, is not one. As FastDecoder
                        // and protowire-go say it, at the value.
                        throw new PxfException(a.value().pos(),
                            "expected '[' for repeated field \"" + a.key() + "\"");
                    } else if (mapEntry || !writesNothing(num, kind, a.value(), meta, registry)) {
                        try {
                            writeField(out, num, kind, a.value(), meta, registry, limits);
                        } catch (RequiredAbsent e) {
                            // A map entry's value is named by the map, as name["k"].
                            throw mapEntry ? e : e.under(a.key());
                        }
                    }
                    recordSet(num, oneofOf, setFields, oneofSet);
                    body.mark(num);
                }
                case Ast.Block b -> {
                    if (b.nameQuoted()) {
                        throw new PxfException(b.pos(), "quoted entry name \"" + b.name()
                            + "\" is only valid inside a keyed repeated field's block (draft -01 §3.13)");
                    }
                    Integer num = fieldNumbers.get(b.name());
                    if (num == null) {
                        throw new PxfException(b.pos(), "unknown field " + Token.quote(b.name()) + " in " + meta.fullName());
                    }
                    checkOneofCollision(num, b.name(), b.pos(), meta, oneofOf, oneofSet);
                    checkBoundOnce(num, b.name(), b.pos(), meta, setFields);
                    int kind = fieldKinds.getOrDefault(num, 0);
                    String keyName = meta.keyFields().get(num);
                    if (keyName != null) {
                        checkCount(num, b.entries().size(), b.name(), b.pos(), counts, limits);
                        writeKeyedBlock(out, num, b.entries(), keyName, meta, registry, limits);
                    } else if (meta.mapFields().contains(num)) {
                        checkCount(num, b.entries().size(), b.name(), b.pos(), counts, limits);
                        writeMap(out, num, b.entries(), meta, registry, limits,
                            mapKeys.computeIfAbsent(num, k -> new HashSet<>()));
                    } else if (repeated.contains(num)) {
                        throw repeatedBlock(b);
                    } else if (kind == K_MESSAGE) {
                        try {
                            writeNestedMessage(out, num, b.entries(), meta, registry, limits);
                        } catch (RequiredAbsent e) {
                            throw mapEntry ? e : e.under(b.name());
                        }
                    } else {
                        throw new PxfException(b.pos(), "field " + Token.quote(b.name())
                            + " is not a message type, cannot use block syntax");
                    }
                    recordSet(num, oneofOf, setFields, oneofSet);
                    body.mark(num);
                }
                case Ast.MapEntry m -> throw new PxfException(m.pos(),
                    "unexpected ':' in message context, use '=' for field assignments");
                // A type entry that opens an Any's block is read by
                // encodeAny; anywhere else it is misplaced. The parser
                // already refuses one that is not first in its block.
                case Ast.TypeEntry t -> throw new PxfException(t.pos(),
                    "@type is allowed only as the first entry of a block bound to google.protobuf.Any (draft -01 \"Any Blocks\")");
            }
        }
    }

    /** HARDENING.md {@code MaxRepeatedCount}, over every binding of a field in one message body. */
    private static void checkCount(
            int num, int added, String name, Position pos, Map<Integer, Integer> counts, DecodeLimits limits) {
        if (counts.merge(num, added, Integer::sum) > limits.maxRepeatedCount()) {
            throw new PxfException(pos, "field \"" + name + "\" exceeds MaxRepeatedCount=" + limits.maxRepeatedCount());
        }
    }

    /**
     * A block on a repeated field that is not keyed. When it spells the keyed
     * form, the quoted entry name is the more specific violation, as in
     * {@code FastDecoder}.
     */
    private static PxfException repeatedBlock(Ast.Block b) {
        if (!b.entries().isEmpty()) {
            Ast.Entry first = b.entries().get(0);
            String quoted = switch (first) {
                case Ast.Block e when e.nameQuoted() -> e.name();
                case Ast.Assignment e when e.keyQuoted() -> e.key();
                default -> null;
            };
            if (quoted != null) {
                return new PxfException(first.pos(), "quoted entry name \"" + quoted
                    + "\" is only valid inside a keyed repeated field's block (draft -01 §3.13)");
            }
        }
        return new PxfException(b.pos(), "repeated field \"" + b.name() + "\" must use list syntax: "
            + b.name() + " = [...]");
    }

    /**
     * Writes the block form of a keyed repeated field (draft -01 §3.13): each
     * entry is one element, in order, and its name supplies the key field.
     * Duplicate entry names within the block, the empty string as a name, and
     * a key-field assignment in an entry's body that disagrees with the name
     * are errors. Mirrors {@code FastDecoder.decodeKeyedBlockBody}.
     *
     * <p>The name reaches the element as an ordinary leading key assignment,
     * so the element's own {@link #encode} treats the key field as set: a
     * {@code (pxf.default)} on it stays out, a {@code (pxf.required)} on it is
     * met. A oneof sibling of it is refused here, where the entry name can be
     * reported.
     */
    private static void writeKeyedBlock(
            CodedOutputStream out,
            int num,
            List<Ast.Entry> entries,
            String keyName,
            PxfMeta meta,
            PxfRegistry registry,
            DecodeLimits limits) {
        String field = fieldNameFor(meta, num);
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Ast.Entry entry : entries) {
            Position pos;
            String name;
            List<Ast.Entry> body;
            switch (entry) {
                case Ast.Block b -> {
                    pos = b.pos();
                    name = b.name();
                    body = b.entries();
                }
                case Ast.Assignment a -> {
                    if (!(a.value() instanceof Ast.BlockVal bv)) {
                        throw new PxfException(a.value().pos(), "keyed entry \"" + a.key() + "\" of field \"" + field
                            + "\" must have a block value ('{ ... }'): the element type is a message");
                    }
                    pos = a.pos();
                    name = a.key();
                    body = bv.entries();
                }
                case Ast.MapEntry m -> throw new PxfException(m.pos(),
                    "expected '{' or '=' after entry name \"" + m.key() + "\" in keyed field \"" + field + "\"");
                case Ast.TypeEntry t -> throw new PxfException(t.pos(),
                    "expected entry name (identifier or string) in keyed field \"" + field + "\"");
            }
            if (name.isEmpty()) {
                throw new PxfException(pos, "empty entry name in keyed field \"" + field
                    + "\": the empty string is not a valid key");
            }
            if (!seen.add(name)) {
                throw new PxfException(pos, "duplicate key \"" + name + "\" in keyed field \"" + field + "\"");
            }
            boolean keySpelled = false;
            for (Ast.Entry e : body) {
                if (e instanceof Ast.Assignment a && !a.keyQuoted() && a.key().equals(keyName)) {
                    keySpelled = true;
                    checkNamedKey(a, keyName, name, field);
                }
                checkKeySibling(e, keyName, name, field, nestedMetaOf(num, meta, registry));
            }
            List<Ast.Entry> element = body;
            if (!keySpelled) {
                element = new ArrayList<>(body.size() + 1);
                element.add(new Ast.Assignment(pos, keyName, new Ast.StringVal(pos, name), List.of(), ""));
                element.addAll(body);
            }
            try {
                writeNestedMessage(out, num, element, meta, registry, limits);
            } catch (RequiredAbsent e) {
                throw e.under(field + "[" + index + "]");
            }
            index++;
        }
    }

    /** A key-field assignment in a named entry's body must agree with the name. */
    private static void checkNamedKey(Ast.Assignment a, String keyName, String entryName, String field) {
        if (a.value() instanceof Ast.NullVal) {
            throw new PxfException(a.pos(), "null on key field \"" + keyName + "\" conflicts with entry name \""
                + entryName + "\" in keyed field \"" + field + "\"");
        }
        if (a.value() instanceof Ast.StringVal s) {
            checkKeyNotEmpty(s, keyName, field);
            if (!s.value().equals(entryName)) {
                throw new PxfException(s.pos(), "key field \"" + keyName + "\" = \"" + s.value()
                    + "\" conflicts with entry name \"" + entryName + "\" in keyed field \"" + field + "\"");
            }
        }
    }

    /**
     * The entry name binds the key field, so binding another member of the
     * key's oneof in the entry's body writes two members. {@code null} counts:
     * it also chooses the member.
     */
    private static void checkKeySibling(Ast.Entry e, String keyName, String entryName, String field, PxfMeta element) {
        String other;
        Position pos;
        switch (e) {
            case Ast.Assignment a -> { other = a.key(); pos = a.pos(); }
            case Ast.Block b -> { other = b.name(); pos = b.pos(); }
            default -> { return; }
        }
        if (other.equals(keyName)) {
            return;
        }
        String oneof = element.oneofOf().get(element.fieldNumbers().get(keyName));
        Integer otherNum = element.fieldNumbers().get(other);
        if (oneof != null && otherNum != null && oneof.equals(element.oneofOf().get(otherNum))) {
            throw new PxfException(pos, "field \"" + other + "\" shares oneof \"" + oneof + "\" with key field \""
                + keyName + "\", which entry name \"" + entryName + "\" already set in keyed field \"" + field + "\"");
        }
    }

    /**
     * The anonymous list form of a keyed field stays legal, duplicates
     * included, but an element may not spell its key as the empty string.
     */
    private static void checkAnonymousKeys(Ast.ListVal list, String keyName, String field) {
        for (Ast.Value v : list.elements()) {
            if (!(v instanceof Ast.BlockVal bv)) {
                continue;
            }
            for (Ast.Entry e : bv.entries()) {
                if (e instanceof Ast.Assignment a && !a.keyQuoted() && a.key().equals(keyName)
                        && a.value() instanceof Ast.StringVal s) {
                    checkKeyNotEmpty(s, keyName, field);
                }
            }
        }
    }

    private static void checkKeyNotEmpty(Ast.StringVal s, String keyName, String field) {
        if (s.value().isEmpty()) {
            throw new PxfException(s.pos(), "explicit empty-string assignment to key field \"" + keyName
                + "\" of keyed field \"" + field + "\": the empty string is not a valid key");
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
            Position pos,
            PxfMeta meta,
            Map<Integer, String> oneofOf,
            Map<String, Integer> oneofSet) {
        String oneof = oneofOf.get(num);
        if (oneof == null) {
            return;
        }
        Integer existing = oneofSet.get(oneof);
        if (existing == null) {
            return;
        }
        // Checked before the second binding, as protowire-go's
        // checkOneofDirect is, so the same member twice reads as the oneof's.
        throw new PxfException(pos, "oneof \"" + oneof + "\": field \"" + fieldName
            + "\" conflicts with already-set field \"" + fieldNameFor(meta, existing) + "\"");
    }

    /**
     * A singular field is bound at most once in one message body (draft -01
     * § Entries and Keys, protowire#347): the second binding is an error, not
     * a replacement or a merge. Repeated and map fields are exempt.
     */
    private static void checkBoundOnce(int num, String name, Position pos, PxfMeta meta, Set<Integer> setFields) {
        if (meta.repeatedFields().contains(num) || meta.mapFields().contains(num)) {
            return;
        }
        if (setFields.contains(num)) {
            throw new PxfException(pos, "field \"" + name + "\" is already set");
        }
    }

    /**
     * The null rule (draft -01 § Booleans, Null, and Identifier Values): a
     * list or map is not nullable, and neither is a field outside
     * {@link PxfMeta#nullableFields()}. A field with (pxf.required) or
     * {@code @required} holds a value, so null on it is rejected as absence
     * is (protowire#569, protowire-go#268). That is read off
     * {@link PxfMeta#requiredFields()} as well, so a companion generated
     * before the plugin left required fields out of the nullable set gets
     * the same verdict.
     */
    private static void checkNullable(int num, String name, Position pos, PxfMeta meta) {
        if (meta.mapFields().contains(num)) {
            throw new PxfException(pos, "null is not allowed on map field \"" + name + "\"");
        }
        if (meta.repeatedFields().contains(num)) {
            throw new PxfException(pos, "null is not allowed on repeated field \"" + name + "\"");
        }
        if (meta.requiredFields().contains(num)) {
            throw new PxfException(pos, "null is not allowed on required field \"" + name
                + "\": a required field holds a value");
        }
        if (!meta.nullableFields().contains(num)) {
            throw new PxfException(pos, nullOnSingular(num, name, meta));
        }
    }

    /**
     * protowire-go's nullNotAllowed for a singular field outside the nullable
     * set. A message field always has presence, so it is refused only for
     * being proto2 required, and so is a scalar outside
     * {@link PxfMeta#implicitPresenceFields()}. A companion that predates
     * that table lists no field there, so it words a plain scalar's null as
     * a required field's; the verdict is the same.
     */
    private static String nullOnSingular(int num, String name, PxfMeta meta) {
        int kind = meta.fieldKinds().getOrDefault(num, 0);
        if (kind == K_MESSAGE) {
            return "null is not allowed on required field \"" + name + "\": it must be set";
        }
        if (!meta.implicitPresenceFields().contains(num)) {
            return "null is not allowed on scalar field \"" + name + "\": the field is required, so it must be set";
        }
        return "null is not allowed on scalar field \"" + name + "\": declare it optional, or as a wrapper type "
            + "(google.protobuf." + wrapperName(kind) + "Value or similar), to make it nullable";
    }

    /** The google.protobuf wrapper stem for a scalar kind, as protowire-go names it in that hint. */
    private static String wrapperName(int kind) {
        return switch (kind) {
            case K_BOOL -> "Bool";
            case K_STRING -> "String";
            case K_BYTES -> "Bytes";
            case K_FLOAT -> "Float";
            case K_DOUBLE -> "Double";
            case K_INT64, K_SINT64, K_SFIXED64 -> "Int64";
            case K_UINT32, K_FIXED32 -> "UInt32";
            case K_UINT64, K_FIXED64 -> "UInt64";
            default -> "Int32";
        };
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
            Body body,
            PxfMeta meta,
            PxfRegistry registry,
            DecodeLimits limits,
            Set<Integer> setFields,
            Map<String, Integer> oneofSet,
            boolean mapEntry) {
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
            int kind = fieldKinds.getOrDefault(num, 0);
            Ast.Value value = defaultValue(kind, e.getValue(), meta, num);
            if (mapEntry || !writesNothing(num, kind, value, meta, registry)) {
                writeField(body.out, num, kind, value, meta, registry, limits);
            }
            recordSet(num, oneofOf, setFields, oneofSet);
            body.mark(num);
        }
    }

    /**
     * The value a {@code (pxf.default)} option denotes, by field kind -- the
     * same rule the full tier applies in {@code FastDecoder.applyDefault}: a
     * string field takes the option text as its value, a bytes field its
     * base64 decoding, and every other kind parses the text as a PXF literal.
     * Parsing a string default as PXF would reject any value that is not a
     * bare identifier ({@code "us-east-1"} stops at the dash), which is where
     * this tier diverged from the JVM one until protowire's annotation gate
     * (protowire#244, #58) ran the shared fixture through both.
     */
    private static Ast.Value defaultValue(int kind, String raw, PxfMeta meta, int num) {
        switch (kind) {
            case 9:  // TYPE_STRING
                return new Ast.StringVal(Position.UNKNOWN, raw);
            case 12: // TYPE_BYTES
                return new Ast.BytesVal(Position.UNKNOWN, Base64.getDecoder().decode(raw));
            default:
                Ast.Document parsed = Parser.parse("__default = " + raw);
                if (parsed.entries().isEmpty() || !(parsed.entries().get(0) instanceof Ast.Assignment def)) {
                    throw new IllegalStateException(
                        "(pxf.default) for field " + fieldNameFor(meta, num) + " in " + meta.fullName() +
                        " did not parse as a value: " + raw);
                }
                return def.value();
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
        // protowire-go names the first one in declaration order; the
        // companion keeps no declaration order, so the lowest number.
        int first = Integer.MAX_VALUE;
        for (Integer req : required) {
            if (!setFields.contains(req) && req < first) {
                first = req;
            }
        }
        if (first != Integer.MAX_VALUE) {
            throw new RequiredAbsent(fieldNameFor(meta, first));
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
            PxfRegistry registry,
            DecodeLimits limits) {
        if (kind == K_ENUM) {
            writeEnum(out, num, v, meta, registry);
            return;
        }
        if (kind == K_MESSAGE) {
            // Well-known type fast paths driven by PxfMeta.wellKnownKinds().
            // Symmetric with LiteWireReader's decoder dispatch — both sides
            // key off the same WKT_* int constants the codegen plugin emits.
            int wkt = meta.wellKnownKinds().getOrDefault(num, PxfMeta.WKT_NONE);

            if (v instanceof Ast.TimestampVal tv) {
                requireWellKnownKind(num, wkt, PxfMeta.WKT_TIMESTAMP, meta, v);
                writeWellKnownSecondsNanos(out, num, tv.value().getEpochSecond(), tv.value().getNano());
                return;
            }
            if (v instanceof Ast.DurationVal dv) {
                requireWellKnownKind(num, wkt, PxfMeta.WKT_DURATION, meta, v);
                // java.time keeps nanos positive; the wire form needs seconds
                // and nanos of the same sign (draft -01 § Timestamps and Durations).
                long seconds = dv.value().getSeconds();
                int nanos = dv.value().getNano();
                if (seconds < 0 && nanos > 0) {
                    seconds++;
                    nanos -= 1_000_000_000;
                }
                writeWellKnownSecondsNanos(out, num, seconds, nanos);
                return;
            }

            if (wkt == PxfMeta.WKT_SECRET && !(v instanceof Ast.BlockVal)) {
                writeSecretLiteral(out, num, v, meta);
                return;
            }

            if (!(v instanceof Ast.BlockVal)) {
                switch (wkt) {
                    case PxfMeta.WKT_BIG_INT   -> { writeBigInt(out, num, parseBigInteger(v, meta, num, limits)); return; }
                    case PxfMeta.WKT_DECIMAL   -> { writeDecimal(out, num, parseBigDecimal(v, meta, num, limits)); return; }
                    case PxfMeta.WKT_BIG_FLOAT -> { writeBigFloat(out, num, parseBigFloat(v, meta, num, limits)); return; }
                    default -> { /* fall through */ }
                }
                int wrapperKind = wrapperInnerKind(wkt);
                if (wrapperKind != -1) {
                    checkScalar(wrapperKind, v, fieldNameFor(meta, num));
                    writeWellKnownValueWrapper(out, num, wrapperKind, v);
                    return;
                }
            }
            // PXF allows `field = { ... }` (BlockVal) as an alternative to the bare-block form.
            if (v instanceof Ast.BlockVal bv) {
                writeNestedMessage(out, num, bv.entries(), meta, registry, limits);
                return;
            }
            throw notAMessageValue(v, wkt, meta, num);
        }
        rejectPrecision(v, fieldNameFor(meta, num));
        checkScalar(kind, v, fieldNameFor(meta, num));
        writeScalar(out, num, kind, v);
    }

    /**
     * Writes a {@code pxf.Secret}'s string literal: {@code value} alone, at
     * field 1. Its block form is an ordinary nested message.
     */
    private static void writeSecretLiteral(CodedOutputStream out, int num, Ast.Value v, PxfMeta meta) {
        if (!(v instanceof Ast.StringVal)) {
            throw new PxfException(v.pos(), "field " + fieldNameFor(meta, num) + " in " + meta.fullName()
                + " is a pxf.Secret: expected a string literal or '{'");
        }
        writeWellKnownValueWrapper(out, num, K_STRING, v);
    }

    /**
     * Maps a {@code WKT_*Value} wrapper kind to the wire kind of its single
     * {@code value} field at index 1, or {@code -1} if {@code wkt} isn't a
     * scalar wrapper. Drives the wrapper fast path in {@link #writeField}.
     */
    private static int wrapperInnerKind(int wkt) {
        return switch (wkt) {
            case PxfMeta.WKT_STRING_VALUE -> K_STRING;
            case PxfMeta.WKT_BYTES_VALUE  -> K_BYTES;
            case PxfMeta.WKT_BOOL_VALUE   -> K_BOOL;
            case PxfMeta.WKT_INT32_VALUE  -> K_INT32;
            case PxfMeta.WKT_INT64_VALUE  -> K_INT64;
            case PxfMeta.WKT_UINT32_VALUE -> K_UINT32;
            case PxfMeta.WKT_UINT64_VALUE -> K_UINT64;
            case PxfMeta.WKT_FLOAT_VALUE  -> K_FLOAT;
            case PxfMeta.WKT_DOUBLE_VALUE -> K_DOUBLE;
            default -> -1;
        };
    }

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
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64 -> integer(v, kind) == 0L;
            // By bits: -0.0 is not the zero value, and protobuf writes it.
            case K_FLOAT  -> Float.floatToRawIntBits(asFloat(v)) == 0;
            case K_DOUBLE -> Double.doubleToRawLongBits(asDouble(v)) == 0L;
            case K_STRING -> asString(v).isEmpty();
            case K_BYTES  -> asBytes(v).length == 0;
            default -> false;
        };
    }

    /**
     * Confirms a TimestampVal / DurationVal landed on a field whose
     * {@link PxfMeta#wellKnownKinds()} entry matches the expected WKT kind.
     * Catches user errors like sticking a timestamp literal into a
     * MESSAGE-typed field that isn't actually {@code google.protobuf.Timestamp}
     * — without this check the encoder would silently produce
     * Timestamp-shaped bytes into a slot that expects something else entirely.
     */
    private static void requireWellKnownKind(int num, int actualWkt, int expectedWkt, PxfMeta meta, Ast.Value v) {
        if (actualWkt != expectedWkt) {
            throw notAMessageValue(v, actualWkt, meta, num);
        }
    }

    /**
     * protowire-go's error for a message field given a value it does not
     * take: the forms the type does take ({@code msgValueForms}), and the
     * token the value starts with.
     */
    private static PxfException notAMessageValue(Ast.Value v, int wkt, PxfMeta meta, int num) {
        String forms = switch (wkt) {
            case PxfMeta.WKT_TIMESTAMP -> "a timestamp literal or '{'";
            case PxfMeta.WKT_DURATION -> "a duration literal or '{'";
            case PxfMeta.WKT_BIG_INT -> "an integer literal or '{'";
            case PxfMeta.WKT_DECIMAL, PxfMeta.WKT_BIG_FLOAT -> "a numeric literal or '{'";
            case PxfMeta.WKT_SECRET -> "a string literal or '{'";
            default -> "'{'";
        };
        return new PxfException(v.pos(), "expected " + forms + " for message field "
            + Token.quote(fieldNameFor(meta, num)) + ", got " + describe(v));
    }

    /** The token {@code v} starts with, as {@link Token#errMsg()} describes one. */
    private static String describe(Ast.Value v) {
        return switch (v) {
            case Ast.StringVal x -> "string (" + Token.quote(x.value()) + ")";
            case Ast.IntVal x -> "integer (" + Token.quote(x.raw()) + ")";
            case Ast.FloatVal x -> "float (" + Token.quote(x.raw()) + ")";
            case Ast.BoolVal x -> "bool (" + Token.quote(Boolean.toString(x.value())) + ")";
            case Ast.IdentVal x -> "identifier (" + Token.quote(x.name()) + ")";
            case Ast.TimestampVal x -> "timestamp (" + Token.quote(x.raw()) + ")";
            case Ast.DurationVal x -> "duration (" + Token.quote(x.raw()) + ")";
            case Ast.BytesVal x -> "bytes";
            case Ast.NullVal x -> "null (\"null\")";
            case Ast.BadVal x -> "a missing value (a tolerant parse's placeholder)";
            case Ast.ListVal x -> "[ (\"[\")";
            case Ast.BlockVal x -> "{ (\"{\")";
        };
    }

    /** The {@link PxfMeta} of message field {@code num}. */
    private static PxfMeta nestedMetaOf(int num, PxfMeta meta, PxfRegistry registry) {
        // Prefer the meta's embedded nested reference (populated by codegen)
        // over a registry lookup. This is the path for codegen-generated
        // <Message>PxfMeta classes; registry is the fallback for hand-built
        // PxfMeta implementations or test fixtures that don't pre-link.
        PxfMeta nestedMeta = meta.nestedMetas().get(num);
        if (nestedMeta == null) {
            // A well-known type's block has a fixed shape, so it needs neither.
            nestedMeta = WellKnownMeta.of(meta.wellKnownKinds().getOrDefault(num, PxfMeta.WKT_NONE));
        }
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
        return nestedMeta;
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
            PxfRegistry registry,
            DecodeLimits limits) {
        PxfMeta nestedMeta = nestedMetaOf(num, meta, registry);
        // Encode the nested entries to a side buffer first so we know the byte length.
        byte[] nestedBytes = encode(entries, nestedMeta, registry, limits, false);
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
     *
     * <p>Entries are written in key order, as protowire-go's deterministic
     * writer puts them: false before true, integers by value, strings by
     * their UTF-8 bytes. Protobuf leaves map order on the wire open, so no
     * reader depends on it, but a fixed order lets the write gate compare
     * this tier's bytes with Go's (protowire#509).
     */
    private static void writeMap(
            CodedOutputStream out,
            int num,
            List<Ast.Entry> entries,
            PxfMeta meta,
            PxfRegistry registry,
            DecodeLimits limits,
            Set<String> seenKeys) {
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
        int valueKind = entryMeta.fieldKinds().getOrDefault(2, K_STRING);

        List<Map.Entry<Ast.Value, byte[]>> encoded = new ArrayList<>();
        for (Ast.Entry entry : entries) {
            if (!(entry instanceof Ast.MapEntry me)) {
                // As protowire-go words and places it: at the '=' or the
                // '{', and at the entry for @type (#222).
                throw switch (entry) {
                    case Ast.Assignment a -> new PxfException(a.equalsPos(), "unexpected '=' in map, use ':' for map entries");
                    case Ast.Block b -> new PxfException(b.bracePos(), "expected ':' after map key, got {");
                    default -> new PxfException(entry.pos(), "expected map key, got @type (\"@type\")");
                };
            }
            if (me.value() instanceof Ast.NullVal) {
                throw new PxfException(me.value().pos(), "null is not allowed as map value in field \""
                    + fieldNameFor(meta, num) + "\"");
            }
            Ast.Value keyVal = mapKeyValue(me.key(), me.keyQuoted(), keyKind, me.pos(), fieldNameFor(meta, num));
            // A scalar value is named by the map field, as protowire-go names it.
            checkScalar(valueKind, me.value(), fieldNameFor(meta, num));
            // Keys compare as values of the key type, so 1 and "1" are one
            // key of an integer-keyed map (protowire#396).
            if (!seenKeys.add(canonicalMapKey(keyVal))) {
                String shown = keyVal instanceof Ast.StringVal ? Token.quote(me.key()) : canonicalMapKey(keyVal);
                throw new PxfException(me.pos(), "map field \"" + fieldNameFor(meta, num)
                    + "\": key " + shown + " is already set");
            }
            List<Ast.Entry> pair = List.of(
                new Ast.Assignment(me.pos(), "key", keyVal, List.of(), ""),
                new Ast.Assignment(me.pos(), "value", me.value(), List.of(), ""));
            try {
                encoded.add(Map.entry(keyVal, encode(pair, entryMeta, registry, limits, true)));
            } catch (RequiredAbsent e) {
                String shown = keyVal instanceof Ast.StringVal ? Token.quote(me.key()) : canonicalMapKey(keyVal);
                throw e.under(fieldNameFor(meta, num) + "[" + shown + "]");
            }
        }
        encoded.sort((x, y) -> compareMapKeys(x.getKey(), y.getKey()));
        for (Map.Entry<Ast.Value, byte[]> e : encoded) {
            byte[] entryBytes = e.getValue();
            try {
                out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
                out.writeUInt32NoTag(entryBytes.length);
                out.writeRawBytes(entryBytes);
            } catch (IOException ex) {
                throw new IllegalStateException("CodedOutputStream write failed", ex);
            }
        }
    }

    /** Key order for {@link #writeMap}; both keys are of the map's key type. */
    private static int compareMapKeys(Ast.Value x, Ast.Value y) {
        return switch (x) {
            case Ast.StringVal s -> java.util.Arrays.compareUnsigned(
                s.value().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ((Ast.StringVal) y).value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case Ast.BoolVal b -> Boolean.compare(b.value(), ((Ast.BoolVal) y).value());
            case Ast.IntVal i -> new BigInteger(i.raw()).compareTo(new BigInteger(((Ast.IntVal) y).raw()));
            default -> throw new IllegalArgumentException("unsupported map key " + x);
        };
    }

    /**
     * Wraps a map-key string (as the parser stored it — unquoted for STRING,
     * digit-form for INT-family, "true"/"false" for BOOL) as the appropriate
     * {@link Ast.Value} for the synthetic entry document. Mirrors the
     * full-runtime decoder's {@code decodeMapKey} contract: {@code quoted}
     * is whether the document spelled the key as a string literal, which
     * decides what it denotes (#76, #82).
     */
    private static Ast.Value mapKeyValue(String key, boolean quoted, int keyKind, Position pos, String field) {
        return switch (keyKind) {
            case K_STRING -> {
                if (!quoted && ("true".equals(key) || "false".equals(key))) {
                    // A bool key matches a map<bool,V> field and nothing
                    // else; the string "true" is spelled quoted.
                    throw new PxfException(pos, "invalid string map key " + key + ": the keyword " + key
                        + " is a bool key; write \"" + key + "\" for the string");
                }
                yield new Ast.StringVal(pos, key);
            }
            case K_INT32, K_INT64, K_UINT32, K_UINT64,
                 K_SINT32, K_SINT64,
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64 -> new Ast.IntVal(pos, key);
            case K_BOOL -> new Ast.BoolVal(pos, boolMapKey(key, quoted, pos, field));
            default -> throw new IllegalArgumentException(
                "unsupported map key kind: " + keyKind +
                " (proto restricts map keys to integral, bool, or string types)");
        };
    }

    /** The value a bound key denotes, for comparing keys across spellings. */
    private static String canonicalMapKey(Ast.Value key) {
        return switch (key) {
            case Ast.StringVal s -> s.value();
            case Ast.BoolVal b -> Boolean.toString(b.value());
            case Ast.IntVal i -> new BigInteger(i.raw()).toString();
            default -> throw new IllegalArgumentException("unsupported map key " + key);
        };
    }

    /**
     * A bool map key's spellings (draft -01 §entries-and-keys, #76): the
     * keyword {@code true} / {@code false} bare, the integers {@code 1} /
     * {@code 0} bare, and the quoted literals {@code "true"} / {@code
     * "false"}. Not {@code Boolean.parseBoolean}, which read every other
     * spelling as false, silently; and not a quoted {@code "1"} / {@code
     * "0"}, which is not a bool literal (#82 gave the AST the quoted flag
     * that tells it from a bare {@code 1}).
     */
    private static boolean boolMapKey(String key, boolean quoted, Position pos, String field) {
        String rule = " for field \"" + field + "\": a bool key is true, false, 0, 1, \"true\" or \"false\"";
        if (quoted) {
            return switch (key) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new PxfException(pos, "invalid bool map key " + Token.quote(key) + rule);
            };
        }
        return switch (key) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new PxfException(pos, "invalid bool map key " + key + rule);
        };
    }

    private static void writeEnum(
            CodedOutputStream out,
            int num,
            Ast.Value v,
            PxfMeta meta,
            PxfRegistry registry) {
        int enumValue = enumNumber(num, v, meta, registry);
        try {
            out.writeEnum(num, enumValue);
        } catch (IOException e) {
            throw new IllegalStateException("CodedOutputStream write failed", e);
        }
    }

    private static int enumNumber(int num, Ast.Value v, PxfMeta meta, PxfRegistry registry) {
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
                throw new PxfException(v.pos(), "unknown enum value " + Token.quote(id.name()) + " for " + enumMeta.fullName());
            }
            enumValue = mapped;
        } else if (v instanceof Ast.IntVal i) {
            // PXF accepts a bare integer for an enum field — used for unknown / forward-compat values.
            try {
                enumValue = Integer.parseInt(i.raw());
            } catch (NumberFormatException e) {
                throw new PxfException(v.pos(), "invalid enum number: " + i.raw());
            }
        } else {
            throw new PxfException(v.pos(), "expected enum name or number for field " + Token.quote(fieldNameFor(meta, num)));
        }
        return enumValue;
    }

    private static void writeScalar(CodedOutputStream out, int num, int kind, Ast.Value v) {
        rejectPrecision(v);
        try {
            switch (kind) {
                case K_STRING   -> out.writeString(num, asString(v));
                case K_BYTES    -> out.writeByteArray(num, asBytes(v));
                case K_BOOL     -> out.writeBool(num, asBool(v));
                case K_INT32    -> out.writeInt32(num, (int) integer(v, K_INT32));
                case K_INT64    -> out.writeInt64(num, integer(v, K_INT64));
                case K_UINT32   -> out.writeUInt32(num, (int) integer(v, K_UINT32));
                case K_UINT64   -> out.writeUInt64(num, integer(v, K_UINT64));
                case K_SINT32   -> out.writeSInt32(num, (int) integer(v, K_SINT32));
                case K_SINT64   -> out.writeSInt64(num, integer(v, K_SINT64));
                case K_FIXED32  -> out.writeFixed32(num, (int) integer(v, K_FIXED32));
                case K_FIXED64  -> out.writeFixed64(num, integer(v, K_FIXED64));
                case K_SFIXED32 -> out.writeSFixed32(num, (int) integer(v, K_SFIXED32));
                case K_SFIXED64 -> out.writeSFixed64(num, integer(v, K_SFIXED64));
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
            PxfRegistry registry,
            DecodeLimits limits) {
        String field = fieldNameFor(meta, num);
        for (Ast.Value v : list.elements()) {
            if (v instanceof Ast.NullVal) {
                throw new PxfException(v.pos(), "null is not allowed in repeated field " + Token.quote(field));
            }
        }
        if (list.elements().isEmpty()) {
            return; // protobuf writes nothing for an empty list, packed or not
        }
        if (!packed) {
            int index = 0;
            for (Ast.Value v : list.elements()) {
                try {
                    writeField(out, num, kind, v, meta, registry, limits);
                } catch (RequiredAbsent e) {
                    throw e.under(field + "[" + index + "]");
                }
                index++;
            }
            return;
        }
        if (kind == K_ENUM) {
            writePackedEnum(out, num, list, meta, registry);
            return;
        }
        for (Ast.Value v : list.elements()) {
            rejectPrecision(v, fieldNameFor(meta, num));
            checkScalar(kind, v, fieldNameFor(meta, num));
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

    /** An enum element needs its table to become an integer, so it takes this path of its own. */
    private static void writePackedEnum(
            CodedOutputStream out, int num, Ast.ListVal list, PxfMeta meta, PxfRegistry registry) {
        int[] values = new int[list.elements().size()];
        int payloadSize = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = enumNumber(num, list.elements().get(i), meta, registry);
            payloadSize += CodedOutputStream.computeEnumSizeNoTag(values[i]);
        }
        try {
            out.writeUInt32NoTag((num << 3) | 2 /* LEN */);
            out.writeUInt32NoTag(payloadSize);
            for (int value : values) {
                out.writeEnumNoTag(value);
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
            case K_INT32                               -> CodedOutputStream.computeInt32SizeNoTag((int) integer(v, K_INT32));
            case K_INT64                               -> CodedOutputStream.computeInt64SizeNoTag(integer(v, K_INT64));
            case K_UINT32                              -> CodedOutputStream.computeUInt32SizeNoTag((int) integer(v, K_UINT32));
            case K_UINT64                              -> CodedOutputStream.computeUInt64SizeNoTag(integer(v, K_UINT64));
            case K_SINT32                              -> CodedOutputStream.computeSInt32SizeNoTag((int) integer(v, K_SINT32));
            case K_SINT64                              -> CodedOutputStream.computeSInt64SizeNoTag(integer(v, K_SINT64));
            case K_FIXED32, K_SFIXED32, K_FLOAT        -> 4;
            case K_FIXED64, K_SFIXED64, K_DOUBLE       -> 8;
            default -> throw new UnsupportedOperationException(
                "packed encoding for kind " + kind + " is not supported (string/bytes/message/group can't pack)");
        };
    }

    private static void writeScalarNoTag(CodedOutputStream out, int kind, Ast.Value v) throws IOException {
        switch (kind) {
            case K_BOOL     -> out.writeBoolNoTag(asBool(v));
            case K_INT32    -> out.writeInt32NoTag((int) integer(v, K_INT32));
            case K_INT64    -> out.writeInt64NoTag(integer(v, K_INT64));
            case K_UINT32   -> out.writeUInt32NoTag((int) integer(v, K_UINT32));
            case K_UINT64   -> out.writeUInt64NoTag(integer(v, K_UINT64));
            case K_SINT32   -> out.writeSInt32NoTag((int) integer(v, K_SINT32));
            case K_SINT64   -> out.writeSInt64NoTag(integer(v, K_SINT64));
            case K_FIXED32  -> out.writeFixed32NoTag((int) integer(v, K_FIXED32));
            case K_FIXED64  -> out.writeFixed64NoTag(integer(v, K_FIXED64));
            case K_SFIXED32 -> out.writeSFixed32NoTag((int) integer(v, K_SFIXED32));
            case K_SFIXED64 -> out.writeSFixed64NoTag(integer(v, K_SFIXED64));
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

    /**
     * Refuses a value of the wrong kind for a scalar field, as protowire-go's
     * consumeScalarAs does: {@code expected integer for field "n"}. A bytes
     * field takes a {@code b"..."} literal only, as in Go and the full tier.
     * Enum and message kinds are checked where they are written.
     */
    private static void checkScalar(int kind, Ast.Value v, String field) {
        String expected = switch (kind) {
            case K_STRING -> v instanceof Ast.StringVal ? null : "string";
            case K_BOOL -> v instanceof Ast.BoolVal ? null : "bool";
            case K_BYTES -> v instanceof Ast.BytesVal ? null : "bytes";
            case K_FLOAT, K_DOUBLE -> v instanceof Ast.FloatVal || v instanceof Ast.IntVal
                || (v instanceof Ast.IdentVal id && Format.isNonFinite(id.name())) ? null : "number";
            case K_INT32, K_INT64, K_UINT32, K_UINT64, K_SINT32, K_SINT64,
                 K_FIXED32, K_FIXED64, K_SFIXED32, K_SFIXED64 -> v instanceof Ast.IntVal ? null : "integer";
            default -> null;
        };
        if (expected != null) {
            throw new PxfException(v.pos(), "expected " + expected + " for field " + Token.quote(field));
        }
    }

    private static String asString(Ast.Value v) {
        if (v instanceof Ast.StringVal s) return s.value();
        throw new IllegalArgumentException("expected string, got " + v.getClass().getSimpleName());
    }

    private static byte[] asBytes(Ast.Value v) {
        if (v instanceof Ast.BytesVal b) return b.value();
        throw new IllegalArgumentException("expected bytes, got " + v.getClass().getSimpleName());
    }

    private static boolean asBool(Ast.Value v) {
        if (v instanceof Ast.BoolVal b) return b.value();
        throw new IllegalArgumentException("expected bool, got " + v.getClass().getSimpleName());
    }

    /**
     * An integer in the range of field kind {@code kind} (draft -01 § Numeric
     * Literals). An unsigned kind keeps its bits in the signed Java type,
     * which is what {@link CodedOutputStream} takes.
     */
    private static long integer(Ast.Value v, int kind) {
        if (!(v instanceof Ast.IntVal i)) {
            throw new IllegalArgumentException("expected integer, got " + v.getClass().getSimpleName());
        }
        try {
            return switch (kind) {
                case K_INT32, K_SINT32, K_SFIXED32 -> Integer.parseInt(i.raw());
                case K_UINT32, K_FIXED32 -> Integer.parseUnsignedInt(i.raw());
                case K_UINT64, K_FIXED64 -> Long.parseUnsignedLong(i.raw());
                default -> Long.parseLong(i.raw());
            };
        } catch (NumberFormatException e) {
            String type = switch (kind) {
                case K_INT32, K_SINT32, K_SFIXED32 -> "int32";
                case K_UINT32, K_FIXED32 -> "uint32";
                case K_UINT64, K_FIXED64 -> "uint64";
                default -> "int64";
            };
            throw new PxfException(i.pos(), "invalid " + type + ": " + i.raw());
        }
    }

    /** A finite literal that overflows is refused, as in the full tier ({@link Format#parseFloat}, #158). */
    private static float asFloat(Ast.Value v) {
        String text;
        if (v instanceof Ast.FloatVal f) text = f.raw();
        else if (v instanceof Ast.IntVal i) text = i.raw();
        else if (v instanceof Ast.IdentVal id && Format.isNonFinite(id.name())) text = id.name();
        else throw new IllegalArgumentException("expected float, got " + v.getClass().getSimpleName());
        try {
            return Format.parseFloat(text);
        } catch (NumberFormatException e) {
            throw new PxfException(v.pos(), e.getMessage());
        }
    }

    /** As {@link #asFloat}, for a {@code double}. */
    private static double asDouble(Ast.Value v) {
        String text;
        if (v instanceof Ast.FloatVal f) text = f.raw();
        else if (v instanceof Ast.IntVal i) text = i.raw();
        else if (v instanceof Ast.IdentVal id && Format.isNonFinite(id.name())) text = id.name();
        else throw new IllegalArgumentException("expected double, got " + v.getClass().getSimpleName());
        try {
            return Format.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new PxfException(v.pos(), e.getMessage());
        }
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

    /** Only a {@code pxf.BigFloat} reads a precision suffix (draft -01 § PXF Annotations, protowire#406). */
    private static void rejectPrecision(Ast.Value v) {
        rejectPrecision(v, null);
    }

    /** {@link #rejectPrecision(Ast.Value)}, naming {@code field} as protowire-go does when it is known. */
    private static void rejectPrecision(Ast.Value v, String field) {
        String prec = v instanceof Ast.IntVal i ? i.prec() : v instanceof Ast.FloatVal f ? f.prec() : "";
        if (!prec.isEmpty()) {
            throw new PxfException(v.pos(), "precision suffix p" + prec + " is allowed only on a pxf.BigFloat"
                + (field == null ? "" : ", not on field \"" + field + "\""));
        }
    }

    private static BigFloats.Value parseBigFloat(Ast.Value v, PxfMeta meta, int num, DecodeLimits limits) {
        String raw, prec;
        if (v instanceof Ast.FloatVal f) { raw = f.raw(); prec = f.prec(); }
        else if (v instanceof Ast.IntVal i) { raw = i.raw(); prec = i.prec(); }
        else {
            throw notAMessageValue(v, PxfMeta.WKT_BIG_FLOAT, meta, num);
        }
        try {
            return BigFloats.parse(raw, prec, limits.maxNumericLiteralDigits(), limits.maxBigFloatExponent());
        } catch (IllegalArgumentException e) {
            throw new PxfException(v.pos(), e.getMessage());
        }
    }

    /** HARDENING.md {@code MaxNumericLiteralDigits}, ahead of a parser that is quadratic in the digits. */
    private static void checkDigits(Ast.Value v, String raw, DecodeLimits limits) {
        int max = limits.maxNumericLiteralDigits();
        if (raw.length() > max && BigFloats.countDigits(raw) > max) {
            throw new PxfException(v.pos(), "numeric literal has " + BigFloats.countDigits(raw)
                + " digits; MaxNumericLiteralDigits=" + max);
        }
    }

    private static BigInteger parseBigInteger(Ast.Value v, PxfMeta meta, int num, DecodeLimits limits) {
        rejectPrecision(v);
        if (v instanceof Ast.IntVal i) {
            checkDigits(v, i.raw(), limits);
            return new BigInteger(i.raw());
        }
        throw notAMessageValue(v, PxfMeta.WKT_BIG_INT, meta, num);
    }

    /** A {@code pxf.Decimal} as its wire fields: the unscaled magnitude, the scale and the sign. */
    private record DecimalParts(BigInteger unscaled, int scale, boolean negative) {}

    private static DecimalParts parseBigDecimal(Ast.Value v, PxfMeta meta, int num, DecodeLimits limits) {
        rejectPrecision(v);
        if (v instanceof Ast.FloatVal f) return boundedDecimal(v, f.raw(), limits);
        if (v instanceof Ast.IntVal   i) return boundedDecimal(v, i.raw(), limits);
        throw notAMessageValue(v, PxfMeta.WKT_DECIMAL, meta, num);
    }

    /**
     * A decimal literal: an optional {@code -}, digits, and an optional
     * fraction, as the full tier's {@code WellKnown.parseDecimal} reads it.
     * An exponent is refused with Go's message ({@code invalid decimal: 1e3}),
     * and the sign of a zero is kept, so {@code -0} sets {@code negative}
     * (#156, #157).
     */
    private static DecimalParts boundedDecimal(Ast.Value v, String raw, DecodeLimits limits) {
        checkDigits(v, raw, limits);
        boolean negative = raw.startsWith("-");
        String digits = negative ? raw.substring(1) : raw;
        int dot = digits.indexOf('.');
        int scale = dot < 0 ? 0 : digits.length() - dot - 1;
        if (dot >= 0) {
            digits = digits.substring(0, dot) + digits.substring(dot + 1);
        }
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new PxfException(v.pos(), "invalid decimal: " + raw);
        }
        // Leading zeros aren't significant, so the digit count doesn't bound
        // the scale (protowire#410). It is bounded as it is on the wire.
        if (scale > limits.maxNumericLiteralDigits()) {
            throw new PxfException(v.pos(), "pxf.Decimal scale " + scale
                + " exceeds MaxNumericLiteralDigits=" + limits.maxNumericLiteralDigits());
        }
        return new DecimalParts(new BigInteger(digits), scale, negative);
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
    private static void writeDecimal(CodedOutputStream out, int num, DecimalParts value) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        try {
            BigInteger unscaledAbs = value.unscaled();
            byte[] unscaledBytes = unscaledAbs.signum() == 0 ? new byte[0] : trimSign(unscaledAbs.toByteArray());
            if (unscaledBytes.length > 0) {
                payloadOut.writeBytes(1, ByteString.copyFrom(unscaledBytes));
            }
            if (value.scale() != 0) {
                payloadOut.writeInt32(2, value.scale());
            }
            if (value.negative()) {
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
     * {@code exponent} (int32 binary power, field 2), {@code prec} (uint32,
     * field 3), and {@code negative} (bool, field 4). Default-zero components
     * omitted, as {@code BigFloats} reads them: see that class for the rule.
     */
    private static void writeBigFloat(CodedOutputStream out, int num, BigFloats.Value value) {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        CodedOutputStream payloadOut = CodedOutputStream.newInstance(payloadBuf);
        try {
            byte[] mantBytes = value.mantissa().signum() == 0 ? new byte[0] : trimSign(value.mantissa().toByteArray());
            if (mantBytes.length > 0) {
                payloadOut.writeBytes(1, ByteString.copyFrom(mantBytes));
            }
            if (value.exponent() != 0) {
                payloadOut.writeInt32(2, value.exponent());
            }
            if (value.prec() != 0) {
                payloadOut.writeUInt32(3, value.prec());
            }
            if (value.negative()) {
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
