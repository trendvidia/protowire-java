// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SBE codec. Construct from one or more {@link FileDescriptor}s; pre-computes templates for
 * every message that has the {@code (sbe.template_id)} option.
 *
 * <p>Wire format follows FIX SBE: little-endian, 8-byte message header, fixed-size root block,
 * repeating groups appended after the root block.
 */
public final class Codec {
    static final int HEADER_SIZE = 8;
    static final int GROUP_HEADER_SIZE = 4;
    static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    static final String ENC_INT8="int8", ENC_INT16="int16", ENC_INT32="int32", ENC_INT64="int64",
            ENC_UINT8="uint8", ENC_UINT16="uint16", ENC_UINT32="uint32", ENC_UINT64="uint64",
            ENC_FLOAT="float", ENC_DOUBLE="double", ENC_CHAR="char";

    final Map<String, MessageTemplate> byName = new HashMap<>();
    final Map<Integer, MessageTemplate> byId = new HashMap<>();

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
        return SbeMarshal.marshal(msg, t);
    }

    public void unmarshal(byte[] data, Message.Builder b) {
        MessageTemplate t = byName.get(b.getDescriptorForType().getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + b.getDescriptorForType().getFullName());
        SbeUnmarshal.unmarshal(data, b, t);
    }

    public DynamicMessage unmarshalDescriptor(byte[] data, Descriptor desc) {
        MessageTemplate t = byName.get(desc.getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + desc.getFullName());
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        SbeUnmarshal.unmarshal(data, b, t);
        return b.build();
    }

    public View view(byte[] data) {
        if (data.length < HEADER_SIZE) throw new IllegalArgumentException("sbe: data too short for header");
        ByteBuffer bb = ByteBuffer.wrap(data).order(ORDER);
        int blockLength = Short.toUnsignedInt(bb.getShort(0));
        int templateId = Short.toUnsignedInt(bb.getShort(2));
        MessageTemplate t = byId.get(templateId);
        if (t == null) throw new IllegalArgumentException("sbe: unknown template id " + templateId);
        if (data.length < HEADER_SIZE + blockLength) throw new IllegalArgumentException("sbe: data too short for root block");
        return new View(data, HEADER_SIZE, blockLength, t.viewSchema);
    }
}
