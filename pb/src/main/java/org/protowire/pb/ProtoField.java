// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java field as a protobuf field with a specific field number.
 * The Java analogue of Go's {@code protowire:"N"} struct tag.
 *
 * <pre>
 * public class Endpoint {
 *     {@literal @}ProtoField(1) String path;
 *     {@literal @}ProtoField(2) String method;
 *     {@literal @}ProtoField(3) int port;
 *     {@literal @}ProtoField(value = 4, zigzag = true) long delta;   // proto3 sint64
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ProtoField {
    int value();

    /**
     * Encode a signed integer field ({@code int}, {@code long}, {@code short},
     * {@code byte} and their boxes, alone, in a {@link java.util.List} or
     * as a map key / value) as proto3 {@code sint32} / {@code sint64} — a
     * zigzag varint — instead of the default {@code int32} / {@code int64}
     * plain varint, sign-extended for negatives. The Java analogue of Go's
     * {@code protowire:"N,zigzag"} tag option; the two spellings agree on
     * exactly one value, zero, so a field must use the same one on both
     * sides.
     */
    boolean zigzag() default false;
}
