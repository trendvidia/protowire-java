package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.protowire.sbe.runtime.MessageTemplate;
import org.protowire.sbe.runtime.SbeConstants;
import org.protowire.sbe.runtime.SbeWireCodec;
import org.protowire.sbe.runtime.View;

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
        SbeWireCodec.unmarshal(data, new MessageWriter(b), t);
    }

    public DynamicMessage unmarshalDescriptor(byte[] data, Descriptor desc) {
        MessageTemplate t = byName.get(desc.getFullName());
        if (t == null) throw new IllegalStateException("sbe: no template for " + desc.getFullName());
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        SbeWireCodec.unmarshal(data, new MessageWriter(b), t);
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
