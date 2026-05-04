package org.protowire.sbe;

import com.google.protobuf.Descriptors.FieldDescriptor;

import java.util.List;

final class GroupTemplate {
    final FieldDescriptor fd;
    final int blockLength;
    final List<FieldTemplate> fields;

    GroupTemplate(FieldDescriptor fd, int blockLength, List<FieldTemplate> fields) {
        this.fd = fd;
        this.blockLength = blockLength;
        this.fields = fields;
    }
}
