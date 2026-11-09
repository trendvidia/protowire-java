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
import org.protowire.sbe.runtime.SbeKinds;
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
    // Keyed by schema id and template id together: a template id is unique
    // only within a schema (draft -01 § SBE Annotations, protowire-go#253).
    final Map<SchemaTemplate, MessageTemplate> byId = new HashMap<>();

    /** A template's identity on the wire: the header's schemaId and templateId. */
    record SchemaTemplate(int schemaId, int templateId) {}
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
        c.names.putAll(names);
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
            // Every header field is 16 bits; a larger id would be written as
            // its low bits, naming another schema (protowire-go#250).
            if (Integer.toUnsignedLong(schema) > SbeKinds.MAX_UINT16) {
                throw new IllegalArgumentException("sbe: file " + fd.getName() + ": (sbe.schema_id) = " + Integer.toUnsignedString(schema)
                        + " is past " + SbeKinds.MAX_UINT16 + ", the largest schemaId SBE can state");
            }
            if (Integer.toUnsignedLong(v) > SbeKinds.MAX_UINT16) {
                throw new IllegalArgumentException("sbe: file " + fd.getName() + ": (sbe.version) = " + Integer.toUnsignedString(v)
                        + " is past " + SbeKinds.MAX_UINT16 + ", the largest version SBE can state");
            }
            for (Descriptor md : fd.getMessageTypes()) {
                c.register(md, schema, v);
            }
        }
        return c;
    }

    /** The message each template was registered for, to tell a duplicate id from a file passed twice. */
    private final Map<SchemaTemplate, String> names = new HashMap<>();

    private void register(Descriptor md, int schemaId, int version) {
        Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
        if (tid != null) {
            MessageTemplate tmpl = TemplateBuilder.build(md, schemaId, version);
            // A second message with the same id would replace the first, and
            // view would read one as the other. The same message seen again,
            // as when a file is passed twice, is not a duplicate.
            SchemaTemplate key = new SchemaTemplate(schemaId, tmpl.templateId);
            String prev = names.get(key);
            if (prev != null && !prev.equals(md.getFullName())) {
                throw new IllegalArgumentException("sbe: messages " + prev + " and " + md.getFullName() + " share (sbe.template_id) = "
                        + tmpl.templateId + " in schema " + schemaId + "; it must be unique within a schema");
            }
            names.put(key, md.getFullName());
            byName.put(md.getFullName(), tmpl);
            byId.put(key, tmpl);
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

    /**
     * A reader over {@code data} without decoding it, for the template the
     * header's schemaId and templateId name. As protowire-go's
     * {@code View}, it checks MaxMessageSize first, and a wire blockLength
     * below the template's, whose fields would be read from past the block.
     */
    public View view(byte[] data) {
        if (data.length > maxMessageSize) {
            throw new IllegalArgumentException("sbe: input of " + data.length + " bytes exceeds MaxMessageSize=" + maxMessageSize);
        }
        if (data.length < SbeConstants.HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for header");
        ByteBuffer bb = ByteBuffer.wrap(data).order(SbeConstants.ORDER);
        int blockLength = Short.toUnsignedInt(bb.getShort(0));
        int templateId = Short.toUnsignedInt(bb.getShort(2));
        int schemaId = Short.toUnsignedInt(bb.getShort(4));
        MessageTemplate t = byId.get(new SchemaTemplate(schemaId, templateId));
        if (t == null) throw new IllegalArgumentException("sbe: unknown template ID " + templateId + " in schema " + schemaId);
        if (blockLength < t.blockLength) {
            throw new IllegalArgumentException("sbe: wire blockLength " + blockLength + " < schema blockLength " + t.blockLength
                    + " for template " + templateId);
        }
        if (data.length < SbeConstants.HEADER_SIZE + blockLength) throw new IllegalArgumentException("sbe: data too short for root block");
        return new View(data, SbeConstants.HEADER_SIZE, blockLength, t.viewSchema);
    }
}
