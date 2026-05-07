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
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ProtoField {
    int value();
}
