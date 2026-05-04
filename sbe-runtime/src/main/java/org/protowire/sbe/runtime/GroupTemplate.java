package org.protowire.sbe.runtime;

import java.util.List;

/**
 * Layout of one repeating group in an SBE message — descriptor-free.
 * Each entry has the same {@code blockLength} bytes; entries are appended
 * after the root block. The codec writes a 4-byte
 * {@code (blockLength, count)} header before the entries.
 */
public final class GroupTemplate {
    public final int fieldNumber;
    public final String name;
    public final int blockLength;
    public final List<FieldTemplate> fields;

    public GroupTemplate(int fieldNumber, String name, int blockLength, List<FieldTemplate> fields) {
        this.fieldNumber = fieldNumber;
        this.name = name;
        this.blockLength = blockLength;
        this.fields = fields;
    }
}
