// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks a {@link Message} (typically a {@link DynamicMessage}) and emits PXF text. Special-cases
 * the well-known types and the PXF big-num types to use sugar syntax.
 */
final class Encoder {
    private final MarshalOptions opts;
    final StringBuilder buf = new StringBuilder();
    private FieldDescriptor nullMaskFd;
    private Set<String> nullSet;
    private String pathPrefix = "";

    Encoder(MarshalOptions opts) { this.opts = opts; }

    /**
     * An encoder for a {@code @dataset} row and its cells
     * ({@link DatasetWriter}): the row's {@code _null} set, and no mask
     * field of its own to skip, as protowire-go's cell encoder has.
     */
    Encoder(MarshalOptions opts, Set<String> nullSet) {
        this.opts = opts;
        this.nullSet = nullSet;
    }

    Set<String> nullSet() { return nullSet; }

    boolean nullSetContains(String path) { return nullSet.contains(path); }

    byte[] encode(Message msg) {
        Descriptor desc = msg.getDescriptorForType();
        nullMaskFd = WellKnown.findNullMaskField(desc);
        nullSet = readNullSet(msg);

        if (!opts.typeUrl().isEmpty()) buf.append("@type ").append(opts.typeUrl()).append("\n\n");
        encodeMessage(msg, 0);
        return buf.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Set<String> readNullSet(Message msg) {
        if (nullMaskFd != null && msg.hasField(nullMaskFd)) {
            Set<String> out = new HashSet<>();
            Message fm = (Message) msg.getField(nullMaskFd);
            FieldDescriptor pathsFd = fm.getDescriptorForType().findFieldByName("paths");
            int n = fm.getRepeatedFieldCount(pathsFd);
            for (int i = 0; i < n; i++) out.add((String) fm.getRepeatedField(pathsFd, i));
            return out;
        }
        if (opts.nullFields() != null) {
            return new HashSet<>(opts.nullFields().nullFields());
        }
        return Collections.emptySet();
    }

    /**
     * Refuses a block or list whose contents sit at {@code level}, the depth a
     * decoder of the output counts there (HARDENING.md § Recursion): a
     * producer must not write a document a reader at the same limit refuses
     * (protowire#502, #167). As protowire-go's encoder words it.
     */
    private void checkDepth(int level) {
        if (level > opts.maxNestingDepth()) {
            throw new PxfException(Position.UNKNOWN, "pxf: nesting depth exceeds MaxNestingDepth=" + opts.maxNestingDepth());
        }
    }

    private void writeIndent(int level) { for (int i = 0; i < level; i++) buf.append(opts.indent()); }

    /** How an unset field is written. */
    private enum Unset { SKIP, ZERO, NULL }

    /**
     * How an unset field is written, so that a reader that applies
     * (pxf.default) and checks (pxf.required) reads the message back
     * (draft -01 § Common Schema Layer; protowire-go#259, #263, #267, #268).
     * A field with neither annotation is left out. One without presence, a
     * plain scalar, list or map, is written as its zero value, {@code []} or
     * {@code {}}, which the default does not replace and which counts as
     * supplied. A required field with presence cannot be written, since a
     * reader refuses it absent and refuses it null, so that is an error. A
     * oneof member whose sibling is set is left out. A message-typed field is
     * written {@code null}, which keeps the default off. An {@code optional}
     * or oneof scalar is left out and reads back as its default. As
     * protowire-go's unsetField, word for word in its error.
     */
    private Unset unsetField(Message msg, FieldDescriptor fd) {
        boolean required = Annotations.isRequired(fd);
        boolean hasDefault = Annotations.getDefault(fd) != null;
        if (!required && !hasDefault) return Unset.SKIP;
        if (!fd.hasPresence()) return Unset.ZERO;
        if (required) {
            // With emitDefaults the field is written as every unset one is:
            // a scalar as its zero, which a reader accepts.
            if (opts.emitDefaults()) return Unset.SKIP;
            throw new PxfException(Position.UNKNOWN,
                    "pxf: " + fd.getFullName() + " is unset, and the field is (pxf.required)");
        }
        var oneof = fd.getContainingOneof();
        if (oneof != null && !oneof.isSynthetic() && msg.getOneofFieldDescriptor(oneof) != null) return Unset.SKIP;
        return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE ? Unset.NULL : Unset.SKIP;
    }

    /**
     * The key field to omit from the NEXT encodeMessage call: the entry name
     * of a keyed-block entry already carries its value (draft -01 §3.13).
     * Consumed by that call so nested messages don't inherit the skip.
     */
    private FieldDescriptor skipKeyFd;

    private void encodeMessage(Message msg, int level) {
        Descriptor d = msg.getDescriptorForType();
        FieldDescriptor skipKey = skipKeyFd;
        skipKeyFd = null;
        for (FieldDescriptor fd : d.getFields()) {
            if (skipKey != null && fd == skipKey) continue;
            if (nullMaskFd != null && pathPrefix.isEmpty() && fd.getNumber() == nullMaskFd.getNumber()) continue;
            String path = pathPrefix + fd.getName();

            // A field that cannot hold null is written as if the mask did
            // not name it: null on it is a decode error (protowire-go #151).
            if (!nullSet.isEmpty() && nullSet.contains(path) && FastDecoder.nullable(fd)) {
                writeIndent(level);
                buf.append(fd.getName()).append(" = null\n");
                continue;
            }

            boolean unset = fd.isRepeated() ? msg.getRepeatedFieldCount(fd) == 0 : !msg.hasField(fd);
            if (unset) {
                Unset how = unsetField(msg, fd);
                if (how == Unset.NULL) {
                    writeIndent(level);
                    buf.append(fd.getName()).append(" = null\n");
                    continue;
                }
                if (how == Unset.ZERO && fd.isRepeated()) {
                    checkDepth(level + 1);
                    writeIndent(level);
                    buf.append(fd.getName()).append(fd.isMapField() ? " = {}\n" : " = []\n");
                    continue;
                }
                if (how == Unset.ZERO) {
                    writeIndent(level);
                    buf.append(fd.getName()).append(" = ");
                    writeScalar(fd, msg.getField(fd));
                    buf.append('\n');
                    continue;
                }
                // SKIP: left out, as before; with emitDefaults the zero below.
            }

            if (fd.isMapField()) {
                if (msg.getRepeatedFieldCount(fd) == 0 && !opts.emitDefaults()) continue;
                encodeMap(msg, fd, level);
                continue;
            }
            if (fd.isRepeated()) {
                if (msg.getRepeatedFieldCount(fd) == 0 && !opts.emitDefaults()) continue;
                encodeList(msg, fd, level);
                continue;
            }
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (!msg.hasField(fd)) continue;
                encodeMessageField(fd, (Message) msg.getField(fd), level);
                continue;
            }

            if (!opts.emitDefaults() && !msg.hasField(fd)) continue;
            writeIndent(level);
            buf.append(fd.getName()).append(" = ");
            writeScalar(fd, msg.getField(fd));
            buf.append('\n');
        }
    }

    void encodeMessageField(FieldDescriptor fd, Message sub, int level) {
        Descriptor md = fd.getMessageType();

        if (hasLiteral(sub)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ");
            writeLiteral(sub);
            buf.append('\n');
            return;
        }
        if (WellKnown.isAny(md) && opts.typeResolver() != null) {
            if (tryEncodeAny(fd, sub, level)) return;
        }

        checkDepth(level + 1);
        writeIndent(level);
        buf.append(fd.getName()).append(" {\n");
        String saved = pathPrefix;
        pathPrefix = pathPrefix + fd.getName() + ".";
        encodeMessage(sub, level + 1);
        pathPrefix = saved;
        writeIndent(level);
        buf.append("}\n");
    }

    /** Whether {@code m} is written as a literal, wherever it is bound, rather than as a block. */
    static boolean hasLiteral(Message m) {
        Descriptor md = m.getDescriptorForType();
        return WellKnown.isTimestamp(md) || durationLiteral(m) || WellKnown.isWrapper(md)
                || WellKnown.isBigInt(md) || WellKnown.isDecimal(md) || WellKnown.isBigFloat(md)
                || secretLiteral(m);
    }

    /** Whether {@code m} is a Duration its literal can hold; a longer one is written as a block. */
    private static boolean durationLiteral(Message m) {
        Descriptor md = m.getDescriptorForType();
        if (!WellKnown.isDuration(md)) return false;
        return TimeFormats.fitsDurationLiteral(
                (long) m.getField(md.findFieldByName("seconds")),
                (int) m.getField(md.findFieldByName("nanos")));
    }

    /** Writes the literal of a message {@link #hasLiteral} accepts. */
    void writeLiteral(Message m) {
        Descriptor md = m.getDescriptorForType();
        if (WellKnown.isTimestamp(md))      buf.append(WellKnown.formatRfc3339(WellKnown.readTimestamp(m)));
        else if (WellKnown.isDuration(md))  buf.append(WellKnown.formatGoDuration(WellKnown.readDuration(m)));
        else if (WellKnown.isWrapper(md)) {
            FieldDescriptor inner = md.findFieldByName("value");
            writeScalar(inner, m.getField(inner));
        } else if (WellKnown.isBigInt(md))  buf.append(WellKnown.readBigInt(m).toString());
        else if (WellKnown.isDecimal(md))   buf.append(WellKnown.readDecimalStr(m));
        else if (WellKnown.isBigFloat(md))  buf.append(WellKnown.readBigFloatStr(m));
        else                                writeSecretLiteral(m);
    }

    /** Whether {@code m} is a Secret its string literal can hold, which keeps nothing but {@code value}. */
    private static boolean secretLiteral(Message m) {
        return WellKnown.isSecret(m.getDescriptorForType()) && !WellKnown.secretHasMetadata(m);
    }

    private void writeSecretLiteral(Message m) {
        FieldDescriptor inner = m.getDescriptorForType().findFieldByName("value");
        writeScalar(inner, m.getField(inner));
    }

    void encodeList(Message msg, FieldDescriptor fd, int level) {
        int n = msg.getRepeatedFieldCount(fd);
        // Keyed repeated field (draft -01 §3.13): emit the keyed block form
        // whenever every element's key is present, non-empty, and distinct;
        // otherwise fall through to the anonymous list form (elements with
        // absent or duplicate keys can only be represented anonymously).
        FieldDescriptor keyFd = Annotations.keyField(fd);
        if (keyFd != null && n > 0 && keyedFormEligible(msg, fd, keyFd)) {
            encodeKeyedList(msg, fd, keyFd, level);
            return;
        }
        checkDepth(level + 1);
        writeIndent(level);
        buf.append(fd.getName()).append(" = [\n");
        for (int i = 0; i < n; i++) {
            Object elem = msg.getRepeatedField(fd, i);
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                Message sub = (Message) elem;
                writeIndent(level + 1);
                if (hasLiteral(sub)) {
                    writeLiteral(sub);
                } else {
                    checkDepth(level + 2);
                    buf.append("{\n");
                    encodeMessage(sub, level + 2);
                    writeIndent(level + 1);
                    buf.append('}');
                }
            } else {
                writeIndent(level + 1);
                writeScalar(fd, elem);
            }
            if (i < n - 1) buf.append(',');
            buf.append('\n');
        }
        writeIndent(level);
        buf.append("]\n");
    }

    private static boolean keyedFormEligible(Message msg, FieldDescriptor fd, FieldDescriptor keyFd) {
        int n = msg.getRepeatedFieldCount(fd);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String key = (String) ((Message) msg.getRepeatedField(fd, i)).getField(keyFd);
            if (key.isEmpty() || !seen.add(key)) return false;
        }
        return true;
    }

    /**
     * The keyed block form: one named block per element, in list order.
     * Entry names are written unquoted when identifier-safe and quoted
     * otherwise; the key field is not additionally emitted inside the
     * entry's block.
     */
    private void encodeKeyedList(Message msg, FieldDescriptor fd, FieldDescriptor keyFd, int level) {
        int n = msg.getRepeatedFieldCount(fd);
        checkDepth(level + 2);
        writeIndent(level);
        buf.append(fd.getName()).append(" {\n");
        for (int i = 0; i < n; i++) {
            Message sub = (Message) msg.getRepeatedField(fd, i);
            String key = (String) sub.getField(keyFd);
            writeIndent(level + 1);
            if (Format.identSafeEntryName(key)) buf.append(key);
            else buf.append('"').append(Format.escape(key)).append('"');
            buf.append(" {\n");
            skipKeyFd = keyFd;
            encodeMessage(sub, level + 2);
            writeIndent(level + 1);
            buf.append("}\n");
        }
        writeIndent(level);
        buf.append("}\n");
    }

    @SuppressWarnings("unchecked")
    void encodeMap(Message msg, FieldDescriptor fd, int level) {
        int n = msg.getRepeatedFieldCount(fd);
        checkDepth(level + 1);
        writeIndent(level);
        buf.append(fd.getName()).append(" = {\n");

        Descriptor entryType = fd.getMessageType();
        FieldDescriptor keyFd = entryType.findFieldByName("key");
        FieldDescriptor valFd = entryType.findFieldByName("value");

        TreeMap<String, Object[]> sorted = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            Message entry = (Message) msg.getRepeatedField(fd, i);
            Object k = entry.getField(keyFd);
            Object v = entry.getField(valFd);
            sorted.put(formatMapKey(keyFd, k), new Object[]{ k, v });
        }

        for (Map.Entry<String, Object[]> kv : sorted.entrySet()) {
            String keyStr = kv.getKey();
            Object v = kv.getValue()[1];
            if (valFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && hasLiteral((Message) v)) {
                writeIndent(level + 1);
                buf.append(keyStr).append(": ");
                writeLiteral((Message) v);
                buf.append('\n');
            } else if (valFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                checkDepth(level + 2);
                writeIndent(level + 1);
                buf.append(keyStr).append(": {\n");
                encodeMessage((Message) v, level + 2);
                writeIndent(level + 1);
                buf.append("}\n");
            } else {
                writeIndent(level + 1);
                buf.append(keyStr).append(": ");
                writeScalar(valFd, v);
                buf.append('\n');
            }
        }
        writeIndent(level);
        buf.append("}\n");
    }

    private String formatMapKey(FieldDescriptor keyFd, Object k) {
        return switch (keyFd.getJavaType()) {
            case STRING -> {
                String s = (String) k;
                yield Format.needsQuoting(s) ? '"' + Format.escape(s) + '"' : s;
            }
            case BOOLEAN -> ((Boolean) k) ? "true" : "false";
            case INT, LONG -> integerText(keyFd, k);
            default -> String.valueOf(k);
        };
    }

    /** An unsigned type keeps its bits in the signed Java type, so it prints unsigned. */
    private static String integerText(FieldDescriptor fd, Object v) {
        return switch (fd.getType()) {
            case UINT32, FIXED32 -> Integer.toUnsignedString((Integer) v);
            case UINT64, FIXED64 -> Long.toUnsignedString((Long) v);
            default -> v.toString();
        };
    }

    void writeScalar(FieldDescriptor fd, Object v) {
        switch (fd.getJavaType()) {
            case STRING -> buf.append('"').append(Format.escape((String) v)).append('"');
            case BOOLEAN -> buf.append(((Boolean) v) ? "true" : "false");
            case INT, LONG -> buf.append(integerText(fd, v));
            case FLOAT -> writeFloat(((Number) v).doubleValue(), true);
            case DOUBLE -> writeFloat(((Number) v).doubleValue(), false);
            case BYTE_STRING -> buf.append("b\"").append(Base64.getEncoder().encodeToString(((ByteString) v).toByteArray())).append('"');
            case ENUM -> writeEnum((com.google.protobuf.Descriptors.EnumValueDescriptor) v);
            default -> throw new IllegalStateException("unsupported scalar kind: " + fd.getJavaType());
        }
    }

    /** A number the schema doesn't name has no identifier, so it is written as an integer. */
    private void writeEnum(com.google.protobuf.Descriptors.EnumValueDescriptor v) {
        if (v.getType().findValueByNumber(v.getNumber()) == null) buf.append(v.getNumber());
        else buf.append(v.getName());
    }

    private void writeFloat(double f, boolean is32) {
        buf.append(is32 ? Format.formatFloat((float) f) : Format.formatDouble(f));
    }

    /**
     * Writes an Any as {@code @type} and a body, if the type resolves and
     * reading that body gives back the Any's {@code value} byte for byte.
     * Otherwise nothing is written and the caller keeps {@code type_url} and
     * {@code value}, which always read back. A reader compares an Any by its
     * bytes, so a body that drops or reorders them changes the message.
     * Decoding is the only complete check: unknown fields at any depth, an
     * order a decoder wouldn't reproduce, a value no literal holds and an
     * annotation's rule all show up in it. Each body is decoded once, so a
     * chain of Anys stops at the limit rather than walking every byte.
     */
    private boolean tryEncodeAny(FieldDescriptor fd, Message anyMsg, int level) {
        Descriptor anyDesc = fd.getMessageType();
        String typeUrl = (String) anyMsg.getField(anyDesc.findFieldByName("type_url"));
        ByteString valueBytes = (ByteString) anyMsg.getField(anyDesc.findFieldByName("value"));
        // At the limit the block has no room for a body, and stopping here
        // bounds a chain of Anys. A body too deep fails inside the try, and
        // the Any stays as its own fields.
        if (typeUrl.isEmpty() || level >= opts.maxNestingDepth()) return false;

        Descriptor inner = opts.typeResolver().findMessageByUrl(typeUrl);
        if (inner == null) return false;

        int start = buf.length();
        String savedPrefix = pathPrefix;
        try {
            Message innerMsg = DynamicMessage.newBuilder(inner).mergeFrom(valueBytes).build();
            writeIndent(level);
            buf.append(fd.getName()).append(" {\n");
            writeIndent(level + 1);
            buf.append("@type = \"").append(Format.escape(typeUrl)).append("\"\n");
            int bodyStart = buf.length();
            pathPrefix = pathPrefix + fd.getName() + ".";
            encodeMessage(innerMsg, level + 1);
            byte[] body = buf.substring(bodyStart).getBytes(StandardCharsets.UTF_8);
            if (FastDecoder.anyBodyReadsBack(body, inner, opts.typeResolver(), level + 1, valueBytes)) {
                writeIndent(level);
                buf.append("}\n");
                return true;
            }
        } catch (InvalidProtocolBufferException | RuntimeException e) {
            // The bytes aren't this type, or no text holds them. They still
            // read back as the Any's own fields.
        } finally {
            pathPrefix = savedPrefix;
        }
        buf.setLength(start);
        return false;
    }
}
