// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import java.io.IOException;

/**
 * The length-prefix framing of a pb stream is invalid: a varint that
 * overflows, or a frame larger than the decoder's maximum. The stream is out
 * of step after it, so the {@link Decoder} throws it again on every later
 * call. As protowire-go's {@code pb.ErrFramingCorrupt}, its message is
 * {@code pb: stream framing corrupt: } and the reason (#207).
 */
public final class FramingCorruptException extends IOException {
    public FramingCorruptException(String reason) {
        super("pb: stream framing corrupt: " + reason);
    }
}
