package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import org.protowire.sbe.runtime.SbeFieldWriter;

/**
 * Full-runtime adapter implementing {@link SbeFieldWriter} on top of a
 * {@link Message.Builder}. Resolves field numbers via the builder's
 * {@link Descriptor} on every call.
 *
 * <p>Value coercion at this boundary:
 * <ul>
 *   <li>ENUM fields — wire codec produces {@link Integer} value
 *       numbers; we look up the matching {@link EnumValueDescriptor}
 *       (creating an unknown-value placeholder if needed for forward
 *       compatibility).</li>
 *   <li>MESSAGE / GROUP-entry fields — borrowed via
 *       {@link #newMessageBuilder} / {@link #newGroupEntry}; the child
 *       writer's {@link #commit} sets / appends the built sub-message
 *       on the parent.</li>
 *   <li>Scalar / string / bytes — passed straight to {@code Builder.setField}.</li>
 * </ul>
 */
final class MessageWriter implements SbeFieldWriter {
    private final Message.Builder builder;
    private final Descriptor desc;
    // Non-null when this writer is a borrowed sub-builder — committing it
    // hands the built message back to its parent at the parent-side field.
    private final MessageWriter parent;
    private final FieldDescriptor parentField;
    private final boolean parentRepeated;

    MessageWriter(Message.Builder builder) {
        this(builder, null, null, false);
    }

    private MessageWriter(Message.Builder builder, MessageWriter parent,
                          FieldDescriptor parentField, boolean parentRepeated) {
        this.builder = builder;
        this.desc = builder.getDescriptorForType();
        this.parent = parent;
        this.parentField = parentField;
        this.parentRepeated = parentRepeated;
    }

    @Override
    public void setField(int fieldNumber, Object value) {
        FieldDescriptor fd = fd(fieldNumber);
        if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM && value instanceof Integer iv) {
            EnumDescriptor ed = fd.getEnumType();
            EnumValueDescriptor ev = ed.findValueByNumber(iv);
            if (ev == null) ev = ed.findValueByNumberCreatingIfUnknown(iv);
            builder.setField(fd, ev);
            return;
        }
        builder.setField(fd, value);
    }

    @Override
    public SbeFieldWriter newMessageBuilder(int fieldNumber) {
        FieldDescriptor fd = fd(fieldNumber);
        return new MessageWriter(builder.newBuilderForField(fd), this, fd, false);
    }

    @Override
    public SbeFieldWriter newGroupEntry(int fieldNumber) {
        FieldDescriptor fd = fd(fieldNumber);
        return new MessageWriter(builder.newBuilderForField(fd), this, fd, true);
    }

    @Override
    public void commit() {
        if (parent == null) return;
        Message built = builder.build();
        if (parentRepeated) {
            parent.builder.addRepeatedField(parentField, built);
        } else {
            parent.builder.setField(parentField, built);
        }
    }

    private FieldDescriptor fd(int fieldNumber) {
        FieldDescriptor fd = desc.findFieldByNumber(fieldNumber);
        if (fd == null) {
            throw new IllegalArgumentException(
                "sbe: field number " + fieldNumber + " not found on " + desc.getFullName());
        }
        return fd;
    }
}
