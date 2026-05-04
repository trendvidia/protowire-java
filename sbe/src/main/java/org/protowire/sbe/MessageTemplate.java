package org.protowire.sbe;

import java.util.List;

final class MessageTemplate {
    final int templateId;
    final int schemaId;
    final int version;
    final int blockLength;
    final List<FieldTemplate> fields;
    final List<GroupTemplate> groups;
    final ViewSchema viewSchema;

    MessageTemplate(int templateId, int schemaId, int version, int blockLength,
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
