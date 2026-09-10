// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.protowire.sbe.runtime.SbeConstants;
import org.protowire.sbe.runtime.SbeWireCodec;

/**
 * SBE codec. Construct from one or more {@link FileDescriptor}s; pre-computes templates for
 * every message that has the {@code (sbe.template_id)} option.
 *
 * <p>Wire format follows FIX SBE: little-endian, 8-byte message header, fixed-size root block,
 * repeating groups appended after the root block.
 *
 * <p>The wire codec lives in {@code :sbe-runtime}'s {@link SbeWireCodec};
 * this class is the descriptor-driven adapter on top of it. {@link MessageReader}
 * / {@link MessageWriter} bridge {@link Message} / {@link Message.Builder} into
 * the runtime's abstract field accessors.
 */
public final class Codec {
    final Map<String, MessageTemplate> byName = new HashMap<>();
    final Map<Integer, MessageTemplate> byId = new HashMap<>();
    // The draft's per-call limits (HARDENING.md § Mandatory limits, #79),
    // fixed on the instance so the Codec stays safe for concurrent use;
    // withLimits returns a Codec sharing the templates.
    private int maxMessageSize = SbeConstants.MAX_MESSAGE_SIZE;
    private int maxRepeatedCount = SbeConstants.MAX_REPEATED_COUNT;

    /**
     * A codec over the same templates that decodes under the given limits:
     * {@code MaxMessageSize} checked before the header is read,
     * {@code MaxRepeatedCount} against each group's declared
     * {@code numInGroup} before any entry is allocated. Both must be
     * positive.
     */
    public Codec withLimits(int maxMessageSize, int maxRepeatedCount) {
        if (maxMessageSize <= 0 || maxRepeatedCount <= 0) {
            throw new IllegalArgumentException("sbe: limits must be positive");
        }
        Codec c = new Codec();
        c.byName.putAll(byName);
        c.byId.putAll(byId);
        c.maxMessageSize = maxMessageSize;
        c.maxRepeatedCount = maxRepeatedCount;
        return c;
    }

    public int maxMessageSize() { return maxMessageSize; }
    public int maxRepeatedCount() { return maxRepeatedCount; }

    public static Codec of(FileDescriptor... files) {
        Codec c = new Codec();
        for (FileDescriptor fd : files) {
            Integer schema = SbeAnnotations.fileUint32(fd, SbeAnnotations.EXT_SCHEMA_ID);
            if (schema == null) throw new IllegalArgumentException("sbe: file " + fd.getName() + " missing (sbe.schema_id)");
            Integer version = SbeAnnotations.fileUint32(fd, SbeAnnotations.EXT_VERSION);
            int v = version == null ? 0 : version;
            for (Descriptor md : fd.getMessageTypes()) {
                c.register(md, schema, v);
            }
        }
        return c;
    }

    private void register(Descriptor md, int schemaId, int version) {
        Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
        if (tid != null) {
            MessageTemplate tmpl = TemplateBuilder.build(md, schemaId, version);
            byName.put(md.getFullName(), tmpl);
            byId.put(tmpl.templateId, tmpl);
        }
        for (Descriptor nested : md.getNestedTypes()) register(nested, schemaId, version);
    }

    public byte[] marshal(Message msg) {
        MessageTemplate t = byName.get(msg.getDescriptorForType().getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + msg.getDescriptorForType().getFullName());
        return SbeWireCodec.marshal(new MessageReader(msg), t);
    }

    public void unmarshal(byte[] data, Message.Builder b) {
        MessageTemplate t = byName.get(b.getDescriptorForType().getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + b.getDescriptorForType().getFullName());
        SbeWireCodec.unmarshal(data, new MessageWriter(b), t, maxMessageSize, maxRepeatedCount);
    }

    public DynamicMessage unmarshalDescriptor(byte[] data, Descriptor desc) {
        MessageTemplate t = byName.get(desc.getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + desc.getFullName());
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        SbeWireCodec.unmarshal(data, new MessageWriter(b), t, maxMessageSize, maxRepeatedCount);
        return b.build();
    }

    public View view(byte[] data) {
        if (data.length < SbeConstants.HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for header");
        ByteBuffer bb = ByteBuffer.wrap(data).order(SbeConstants.ORDER);
        int blockLength = Short.toUnsignedInt(bb.getShort(0));
        int templateId = Short.toUnsignedInt(bb.getShort(2));
        MessageTemplate t = byId.get(templateId);
        if (t == null) throw new IllegalArgumentException("sbe: unknown template id " + templateId);
        if (data.length < SbeConstants.HEADER_SIZE + blockLength) throw new IllegalArgumentException("sbe: data too short for root block");
        return new View(data, SbeConstants.HEADER_SIZE, blockLength, t.viewSchema);
    }
}
