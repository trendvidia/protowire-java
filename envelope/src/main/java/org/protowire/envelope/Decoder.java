// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.envelope;

import org.protowire.envelope.v1.Envelope;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the {@link Envelope}s an {@link Encoder} writes, as protowire-go's
 * {@code envelope.Decoder} (#207). Framing and its errors are pb's
 * {@link org.protowire.pb.Decoder}'s: a corrupt frame throws
 * {@link org.protowire.pb.FramingCorruptException}, and both it and a
 * truncated stream are thrown again on every later call.
 */
public final class Decoder {
    private final org.protowire.pb.Decoder frames;

    public Decoder(InputStream in) { this.frames = new org.protowire.pb.Decoder(in); }

    public void setMaxFrameSize(int n) { frames.setMaxFrameSize(n); }

    /** The next envelope, or null at the end of the stream, between frames. */
    public Envelope decode() throws IOException {
        byte[] frame = frames.nextFrame();
        return frame == null ? null : Envelope.parseFrom(frame);
    }
}
