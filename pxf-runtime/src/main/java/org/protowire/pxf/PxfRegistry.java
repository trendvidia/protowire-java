package org.protowire.pxf;

/**
 * Maps proto-package-qualified message and enum names back to their
 * codegen-emitted metadata. The lite-runtime encoder needs this to resolve
 * the {@link PxfMeta} of a nested message field (so it can recurse) and the
 * {@link PxfEnum} of an enum-typed field (so it can translate identifier
 * tokens to integer wire values).
 *
 * <p>Implementations are expected to look up by the {@code fullName()} that
 * each {@link PxfMeta}/{@link PxfEnum} reports — the same string that the
 * generated tables use in their {@code MESSAGE_TYPES} / {@code ENUM_TYPES}
 * cells.
 */
public interface PxfRegistry {

    /** Returns the registered {@link PxfMeta} for the given full name, or {@code null}. */
    PxfMeta lookupMessage(String fullName);

    /** Returns the registered {@link PxfEnum} for the given full name, or {@code null}. */
    PxfEnum lookupEnum(String fullName);
}
