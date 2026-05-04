package org.protowire.sbe.runtime;

/**
 * Descriptor-free read accessor over a typed protobuf message — what
 * {@link SbeWireCodec#marshal} drives during encode. Implementations
 * adapt the message API of their tier:
 *
 * <ul>
 *   <li>Full-runtime ({@code :sbe}): wraps {@code com.google.protobuf.Message},
 *       resolves field numbers via {@code Descriptor.findFieldByNumber}, unwraps
 *       {@code EnumValueDescriptor} to {@link Integer}.</li>
 *   <li>Lite-runtime (future {@code :sbe-android}): wraps a typed
 *       {@code MessageLite} via codegen-emitted typed-getter dispatch.</li>
 * </ul>
 *
 * <p>Value types returned from {@link #getField(int)} follow the field's
 * {@link FieldTemplate#kind kind}:
 * <table>
 *   <tr><th>kind</th><th>Java value</th></tr>
 *   <tr><td>BOOL</td><td>{@link Boolean}</td></tr>
 *   <tr><td>INT32 / SINT32 / SFIXED32</td><td>{@link Integer}</td></tr>
 *   <tr><td>UINT32 / FIXED32</td><td>{@link Integer} (unsigned, two's-complement)</td></tr>
 *   <tr><td>INT64 / SINT64 / SFIXED64 / UINT64 / FIXED64</td><td>{@link Long}</td></tr>
 *   <tr><td>FLOAT</td><td>{@link Float}</td></tr>
 *   <tr><td>DOUBLE</td><td>{@link Double}</td></tr>
 *   <tr><td>STRING</td><td>{@link String}</td></tr>
 *   <tr><td>BYTES</td><td>{@code com.google.protobuf.ByteString} or {@code byte[]}</td></tr>
 *   <tr><td>ENUM</td><td>{@link Integer} (enum value number)</td></tr>
 * </table>
 *
 * <p>For MESSAGE-typed fields use {@link #getMessageField} or {@link #getRepeatedMessage}.
 */
public interface SbeFieldReader {

    /** Number of entries in a repeated (group) field. */
    int getRepeatedCount(int fieldNumber);

    /** Read a non-message scalar / string / bytes / enum field. */
    Object getField(int fieldNumber);

    /** Read a non-repeated message-typed field, returning a recursive reader. */
    SbeFieldReader getMessageField(int fieldNumber);

    /** Read the {@code index}th entry of a repeated message field, returning a recursive reader. */
    SbeFieldReader getRepeatedMessage(int fieldNumber, int index);
}
