package org.protowire.pxf;

import java.util.Map;

/**
 * Per-enum descriptor metadata sibling to {@link PxfMeta}. Generated
 * {@code <Enum>PxfEnum} classes from {@code protoc-gen-pxf-java-meta}
 * implement this contract — the lite-runtime encoder consults it to
 * translate enum value identifiers in PXF text (e.g. {@code STATUS_ACTIVE})
 * to the integer wire form.
 */
public interface PxfEnum {

    /**
     * The proto-package-qualified enum name, e.g. {@code "sample.Status"} or
     * {@code "sample.Person.Gender"} for a nested enum. Used as the lookup
     * key in {@link PxfRegistry#lookupEnum(String)}.
     */
    String fullName();

    /** Enum value name → integer. */
    Map<String, Integer> values();

    /**
     * Integer → enum value name. When multiple names share the same integer
     * (proto enum aliasing), the first declaration wins.
     */
    Map<Integer, String> names();
}
