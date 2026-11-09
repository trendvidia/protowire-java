// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import java.io.IOException;

/**
 * The SOFH framing of an SBE stream is invalid: an encoding type other than
 * SBE little-endian, a total length below the header's, or a frame larger
 * than the decoder's maximum. The stream is out of step after it, so the
 * {@link Decoder} throws it again on every later call. As protowire-go's
 * {@code sbe.ErrFramingCorrupt}, its message is
 * {@code sbe: stream framing corrupt: } and the reason (#207).
 */
public final class FramingCorruptException extends IOException {
    public FramingCorruptException(String reason) {
        super("sbe: stream framing corrupt: " + reason);
    }
}
