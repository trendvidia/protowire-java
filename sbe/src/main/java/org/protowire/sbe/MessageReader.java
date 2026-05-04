package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import org.protowire.sbe.runtime.SbeFieldReader;

/**
 * Full-runtime adapter implementing {@link SbeFieldReader} on top of a
 * typed {@link Message}. Resolves field numbers via the message's
 * {@link Descriptor} on every call (cheap — descriptor field-number
 * lookup is a hash map probe).
 *
 * <p>Value coercion at this boundary:
 * <ul>
 *   <li>ENUM fields — {@code msg.getField} returns an
 *       {@link EnumValueDescriptor}; we unwrap to its {@link Integer}
 *       value number, matching the wire codec's expected per-kind type.</li>
 *   <li>MESSAGE fields — wrapped recursively in a child
 *       {@link MessageReader}.</li>
 *   <li>Scalar / string / bytes — passed through; the underlying types
 *       ({@code Boolean}, {@code Integer}, {@code Long}, {@code Float},
 *       {@code Double}, {@code String}, {@code ByteString}) match the
 *       wire codec's per-kind contract directly.</li>
 * </ul>
 */
final class MessageReader implements SbeFieldReader {
    private final Message msg;
    private final Descriptor desc;

    MessageReader(Message msg) {
        this.msg = msg;
        this.desc = msg.getDescriptorForType();
    }

    @Override
    public int getRepeatedCount(int fieldNumber) {
        return msg.getRepeatedFieldCount(fd(fieldNumber));
    }

    @Override
    public Object getField(int fieldNumber) {
        FieldDescriptor fd = fd(fieldNumber);
        Object v = msg.getField(fd);
        if (v instanceof EnumValueDescriptor ev) {
            return ev.getNumber();
        }
        return v;
    }

    @Override
    public SbeFieldReader getMessageField(int fieldNumber) {
        FieldDescriptor fd = fd(fieldNumber);
        return new MessageReader((Message) msg.getField(fd));
    }

    @Override
    public SbeFieldReader getRepeatedMessage(int fieldNumber, int index) {
        FieldDescriptor fd = fd(fieldNumber);
        return new MessageReader((Message) msg.getRepeatedField(fd, index));
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
