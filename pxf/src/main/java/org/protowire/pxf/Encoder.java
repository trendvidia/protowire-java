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
    private final StringBuilder buf = new StringBuilder();
    private FieldDescriptor nullMaskFd;
    private Set<String> nullSet;
    private String pathPrefix = "";

    Encoder(MarshalOptions opts) { this.opts = opts; }

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

    private void writeIndent(int level) { for (int i = 0; i < level; i++) buf.append(opts.indent()); }

    private void encodeMessage(Message msg, int level) {
        Descriptor d = msg.getDescriptorForType();
        for (FieldDescriptor fd : d.getFields()) {
            if (nullMaskFd != null && pathPrefix.isEmpty() && fd.getNumber() == nullMaskFd.getNumber()) continue;
            String path = pathPrefix + fd.getName();

            if (!nullSet.isEmpty() && nullSet.contains(path)) {
                writeIndent(level);
                buf.append(fd.getName()).append(" = null\n");
                continue;
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

    private void encodeMessageField(FieldDescriptor fd, Message sub, int level) {
        Descriptor md = fd.getMessageType();

        if (WellKnown.isTimestamp(md)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ").append(TimeFormats.formatRfc3339(WellKnown.readTimestamp(sub))).append('\n');
            return;
        }
        if (WellKnown.isDuration(md)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ").append(TimeFormats.formatGoDuration(WellKnown.readDuration(sub))).append('\n');
            return;
        }
        if (WellKnown.isWrapper(md)) {
            FieldDescriptor inner = md.findFieldByName("value");
            writeIndent(level);
            buf.append(fd.getName()).append(" = ");
            writeScalar(inner, sub.getField(inner));
            buf.append('\n');
            return;
        }
        if (WellKnown.isBigInt(md)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ").append(WellKnown.readBigInt(sub).toString()).append('\n');
            return;
        }
        if (WellKnown.isDecimal(md)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ").append(WellKnown.readDecimalStr(sub)).append('\n');
            return;
        }
        if (WellKnown.isBigFloat(md)) {
            writeIndent(level);
            buf.append(fd.getName()).append(" = ").append(WellKnown.readBigFloatStr(sub)).append('\n');
            return;
        }
        if (WellKnown.isAny(md) && opts.typeResolver() != null) {
            if (tryEncodeAny(fd, sub, level)) return;
        }

        writeIndent(level);
        buf.append(fd.getName()).append(" {\n");
        String saved = pathPrefix;
        pathPrefix = pathPrefix + fd.getName() + ".";
        encodeMessage(sub, level + 1);
        pathPrefix = saved;
        writeIndent(level);
        buf.append("}\n");
    }

    private void encodeList(Message msg, FieldDescriptor fd, int level) {
        int n = msg.getRepeatedFieldCount(fd);
        writeIndent(level);
        buf.append(fd.getName()).append(" = [\n");
        for (int i = 0; i < n; i++) {
            Object elem = msg.getRepeatedField(fd, i);
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                Message sub = (Message) elem;
                Descriptor md = fd.getMessageType();
                writeIndent(level + 1);
                if (WellKnown.isTimestamp(md))      buf.append(TimeFormats.formatRfc3339(WellKnown.readTimestamp(sub)));
                else if (WellKnown.isDuration(md))  buf.append(TimeFormats.formatGoDuration(WellKnown.readDuration(sub)));
                else if (WellKnown.isWrapper(md)) {
                    FieldDescriptor inner = md.findFieldByName("value");
                    writeScalar(inner, sub.getField(inner));
                } else if (WellKnown.isBigInt(md))  buf.append(WellKnown.readBigInt(sub).toString());
                else if (WellKnown.isDecimal(md))   buf.append(WellKnown.readDecimalStr(sub));
                else if (WellKnown.isBigFloat(md))  buf.append(WellKnown.readBigFloatStr(sub));
                else {
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

    @SuppressWarnings("unchecked")
    private void encodeMap(Message msg, FieldDescriptor fd, int level) {
        int n = msg.getRepeatedFieldCount(fd);
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
            if (valFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
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
            case INT -> Integer.toString((Integer) k);
            case LONG -> Long.toString((Long) k);
            default -> String.valueOf(k);
        };
    }

    private void writeScalar(FieldDescriptor fd, Object v) {
        switch (fd.getJavaType()) {
            case STRING -> buf.append('"').append(Format.escape((String) v)).append('"');
            case BOOLEAN -> buf.append(((Boolean) v) ? "true" : "false");
            case INT -> buf.append((int) v);
            case LONG -> buf.append((long) v);
            case FLOAT -> writeFloat(((Number) v).doubleValue(), true);
            case DOUBLE -> writeFloat(((Number) v).doubleValue(), false);
            case BYTE_STRING -> buf.append("b\"").append(Base64.getEncoder().encodeToString(((ByteString) v).toByteArray())).append('"');
            case ENUM -> buf.append(v.toString());
            default -> throw new IllegalStateException("unsupported scalar kind: " + fd.getJavaType());
        }
    }

    private void writeFloat(double f, boolean is32) {
        if (Double.isNaN(f)) { buf.append("nan"); return; }
        if (Double.isInfinite(f)) { buf.append(f > 0 ? "inf" : "-inf"); return; }
        if (is32) buf.append(Float.toString((float) f));
        else      buf.append(Double.toString(f));
    }

    private boolean tryEncodeAny(FieldDescriptor fd, Message anyMsg, int level) {
        Descriptor anyDesc = fd.getMessageType();
        String typeUrl = (String) anyMsg.getField(anyDesc.findFieldByName("type_url"));
        ByteString valueBytes = (ByteString) anyMsg.getField(anyDesc.findFieldByName("value"));
        if (typeUrl.isEmpty()) return false;

        Descriptor inner = opts.typeResolver().findMessageByUrl(typeUrl);
        if (inner == null) return false;

        DynamicMessage.Builder ib = DynamicMessage.newBuilder(inner);
        try {
            ib.mergeFrom(valueBytes);
        } catch (InvalidProtocolBufferException e) {
            return false;
        }
        Message innerMsg = ib.build();

        writeIndent(level);
        buf.append(fd.getName()).append(" {\n");
        writeIndent(level + 1);
        buf.append("@type = \"").append(Format.escape(typeUrl)).append("\"\n");
        encodeMessage(innerMsg, level + 1);
        writeIndent(level);
        buf.append("}\n");
        return true;
    }
}
