package org.protowire.sbe.runtime;

import java.util.List;

/**
 * Layout of one field inside an SBE message block — descriptor-free.
 * The {@code fieldNumber} + {@code kind} pair is what the wire codec
 * dispatches off; the descriptor-driven {@link SbeFieldReader} /
 * {@link SbeFieldWriter} adapters resolve {@code fieldNumber} to a
 * full-runtime {@code FieldDescriptor} or a lite-runtime typed-getter
 * lookup as needed.
 *
 * <p>{@code kind} follows {@code FieldDescriptorProto.Type} (1..18, e.g.
 * 5 = INT32, 9 = STRING, 12 = BYTES, 14 = ENUM). Stable across protobuf
 * versions, identical to the constants {@code :pxf-runtime}'s
 * {@code PxfMeta} uses.
 *
 * <p>{@code composite} is non-null when the field's target type is a
 * sub-message whose layout is recursively flattened into the parent's
 * fixed-size block (FIX SBE composite types). For non-composite scalar /
 * string / bytes / enum fields it stays {@code null}.
 */
public final class FieldTemplate {
    public final int fieldNumber;
    public final String name;
    public final int kind;
    public final int offset;
    public final int size;
    public final String encoding;
    public final List<FieldTemplate> composite;
    public ViewSchema compositeView;

    public FieldTemplate(
            int fieldNumber,
            String name,
            int kind,
            int offset,
            int size,
            String encoding,
            List<FieldTemplate> composite) {
        this.fieldNumber = fieldNumber;
        this.name = name;
        this.kind = kind;
        this.offset = offset;
        this.size = size;
        this.encoding = encoding;
        this.composite = composite;
    }
}
