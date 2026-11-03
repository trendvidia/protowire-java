// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8 handling for {@code (sbe.length)} string fields, shared by
 * {@link SbeWireCodec} and {@code View}.
 */
public final class SbeUtf8 {
    private SbeUtf8() {}

    /**
     * Decodes {@code len} bytes at {@code off} as UTF-8, rejecting any byte
     * sequence that is not UTF-8 (HARDENING.md § UTF-8): a string field is
     * valid UTF-8 whatever the source, and a {@code char[]} says nothing about
     * its own encoding. {@code new String(bytes, UTF_8)} would substitute
     * U+FFFD silently, which HARDENING.md forbids by name.
     *
     * @throws IllegalArgumentException if the bytes are not UTF-8
     */
    public static String decode(byte[] data, int off, int len, String field) {
        try {
            // A fresh decoder reports malformed input rather than replacing it.
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(data, off, len))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("sbe: invalid UTF-8 in string field " + field);
        }
    }
}
