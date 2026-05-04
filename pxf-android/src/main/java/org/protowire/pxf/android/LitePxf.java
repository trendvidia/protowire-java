package org.protowire.pxf.android;

import com.google.protobuf.MessageLite;
import org.protowire.pxf.Format;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;

/**
 * Top-level convenience facade over the lite-runtime PXF codec — pairs the
 * marshal direction (typed {@link MessageLite} → PXF text) with the encode
 * primitives in {@link LiteWireWriter}.
 *
 * <p>The marshal pipeline is {@code msg.toByteArray() →}
 * {@link LiteWireReader#toAst} {@code → Format.formatDocument}. Each piece
 * is independently usable; this facade exists so the common case is a
 * one-liner.
 */
public final class LitePxf {
    private LitePxf() {}

    /**
     * Serialize a typed message back to PXF text. Uses {@link MessageLite#toByteArray()}
     * as the source of truth — proto3 default-omission applies, so fields at
     * their default value are <em>not</em> emitted in the resulting text.
     *
     * <p>Output ordering is by field number, not declaration order
     * (deterministic, matches protobuf's tag-ordered serialization).
     */
    public static String marshal(MessageLite msg, PxfMeta meta) {
        return Format.formatDocument(LiteWireReader.toAst(msg.toByteArray(), meta));
    }

    /**
     * Three-arg variant that takes an explicit {@link PxfRegistry} for
     * resolving cross-message references not embedded in the meta graph.
     * Codegen-emitted {@code <Message>PxfMeta} classes pre-link their
     * dependencies via {@link PxfMeta#nestedMetas()} / {@link PxfMeta#enumMetas()},
     * so the registry is only needed for hand-built metas or test fixtures.
     */
    public static String marshal(MessageLite msg, PxfMeta meta, PxfRegistry registry) {
        return Format.formatDocument(LiteWireReader.toAst(msg.toByteArray(), meta, registry));
    }
}
