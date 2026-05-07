// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;

/**
 * Resolves protobuf type URLs to descriptors, used for {@code google.protobuf.Any} sugar
 * encoding/decoding. A {@code null} resolver disables Any sugar (Any is then encoded/decoded
 * as a regular message with {@code type_url} + {@code value}).
 */
public interface TypeResolver {
    Descriptor findMessageByUrl(String url);
}
