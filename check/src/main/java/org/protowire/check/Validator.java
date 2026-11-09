// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

/**
 * Runs data validation over a decoded value, as protowire-go's
 * {@code check.Validator} (#205). The pxf, pb and sbe decoders take one as
 * an option and call it, through {@link Check#validate}, after a successful
 * decode.
 *
 * <p>{@code decoded} is a {@code com.google.protobuf.Message} for the pxf and
 * sbe decoders, and the bound object for the pb decoder. An engine that
 * handles only proto messages throws for a value it cannot validate.
 *
 * <p>The returned {@link Report} carries data violations; a thrown exception
 * is reserved for engine failure (an unresolvable schema, a bad constraint
 * expression, an unsupported value type), and propagates from the decode as
 * it is. A clean pass returns null or an empty report. Implementations must
 * be safe for concurrent use: a decoder may share one validator across
 * threads.
 */
@FunctionalInterface
public interface Validator {
    Report validate(Object decoded);
}
