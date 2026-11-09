// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.envelope;

import org.protowire.envelope.v1.Envelope;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes {@link Envelope}s to a stream in pb's varint-delimited framing, as
 * protowire-go's {@code envelope.Encoder} (#207). The framing is pb's
 * {@link org.protowire.pb.Encoder}, so it has one implementation.
 */
public final class Encoder {
    private final org.protowire.pb.Encoder frames;

    public Encoder(OutputStream out) { this.frames = new org.protowire.pb.Encoder(out); }

    public void encode(Envelope env) throws IOException {
        frames.encodeFrame(env.toByteArray());
    }
}
