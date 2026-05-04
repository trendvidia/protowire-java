package org.protowire.sbe.runtime;

import java.util.List;

/**
 * Layout of one SBE-templated message — descriptor-free. Built by
 * {@code :sbe}'s {@code TemplateBuilder} from a full protobuf
 * {@code Descriptor}, or (in the future) by the
 * {@code protoc-gen-pxf-java-meta} plugin's lite-mode SBE emit.
 */
public final class MessageTemplate {
    public final int templateId;
    public final int schemaId;
    public final int version;
    public final int blockLength;
    public final List<FieldTemplate> fields;
    public final List<GroupTemplate> groups;
    public final ViewSchema viewSchema;

    public MessageTemplate(int templateId, int schemaId, int version, int blockLength,
                           List<FieldTemplate> fields, List<GroupTemplate> groups, ViewSchema view) {
        this.templateId = templateId;
        this.schemaId = schemaId;
        this.version = version;
        this.blockLength = blockLength;
        this.fields = fields;
        this.groups = groups;
        this.viewSchema = view;
    }
}
