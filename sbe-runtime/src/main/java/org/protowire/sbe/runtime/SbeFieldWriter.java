package org.protowire.sbe.runtime;

/**
 * Descriptor-free write accessor over a typed protobuf message builder
 * — what {@link SbeWireCodec#unmarshal} drives during decode. Mirrors
 * {@link SbeFieldReader}'s value-type contract; see its Javadoc for the
 * per-{@link FieldTemplate#kind kind} expected types.
 *
 * <p>Composite (sub-message) and group-entry writes use a builder
 * borrow / commit pattern:
 *
 * <ol>
 *   <li>{@link #newMessageBuilder} or {@link #newGroupEntry} returns a
 *       child writer.</li>
 *   <li>The codec writes fields into the child via {@link #setField} /
 *       recursion.</li>
 *   <li>{@link #commit} hands the built sub-message back to its parent —
 *       {@code parent.setField} for composite, {@code parent.addRepeatedField}
 *       for group entries. {@link #commit} on a top-level writer is a no-op.</li>
 * </ol>
 */
public interface SbeFieldWriter {

    /** Set a non-message scalar / string / bytes / enum field. */
    void setField(int fieldNumber, Object value);

    /** Borrow a builder for a non-repeated message-typed field; commit writes it back. */
    SbeFieldWriter newMessageBuilder(int fieldNumber);

    /** Borrow a builder for a single group-entry; commit appends it. */
    SbeFieldWriter newGroupEntry(int fieldNumber);

    /** Hand a sub-message builder back to its parent. No-op at the top level. */
    void commit();
}
