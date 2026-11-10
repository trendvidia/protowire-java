// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The payload of a {@code b"…"} literal, decoded as protowire-go decodes it
 * (draft §3.7, RFC 4648 §5): the standard or the URL-safe alphabet, with or
 * without padding. Go's {@code decodeBase64Lenient} tries its four encodings
 * in turn, and when none takes the payload reports the standard one's error,
 * "illegal base64 data at input byte N". This is a port of Go's
 * {@code encoding/base64} decoder for those four, so the two accept the same
 * payloads and point at the same byte (#208).
 */
final class Base64Literal {
    private Base64Literal() {}

    /** A payload none of the four encodings takes, and the byte Go's standard encoding points at. */
    static final class Invalid extends Exception {
        final int offset;

        Invalid(int offset) {
            super("illegal base64 data at input byte " + offset, null, false, false);
            this.offset = offset;
        }
    }

    private static final byte[] STD = map("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/");
    private static final byte[] URL = map("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_");

    private static byte[] map(String alphabet) {
        byte[] m = new byte[256];
        Arrays.fill(m, (byte) 0xff);
        for (int i = 0; i < alphabet.length(); i++) m[alphabet.charAt(i)] = (byte) i;
        return m;
    }

    /** Whether the payload decodes in any of the four encodings. */
    static boolean valid(String raw) {
        try {
            decode(raw);
            return true;
        } catch (Invalid e) {
            return false;
        }
    }

    /** The decoded bytes, from the first of standard, raw standard, URL-safe and raw URL-safe that takes the payload. */
    static byte[] decode(String raw) throws Invalid {
        byte[] src = raw.getBytes(StandardCharsets.UTF_8);
        Invalid first;
        try {
            return decode(src, STD, true);
        } catch (Invalid e) {
            first = e;
        }
        for (Object[] enc : new Object[][] {{STD, false}, {URL, true}, {URL, false}}) {
            try {
                return decode(src, (byte[]) enc[0], (Boolean) enc[1]);
            } catch (Invalid e) {
                // try the next
            }
        }
        throw first;
    }

    /**
     * A {@code @proto b"…"} descriptor body: the standard alphabet, then the
     * URL-safe one, both padded, and the standard one's error, as
     * protowire-go's parseProtoDirective decodes it.
     */
    static byte[] decodeDescriptor(String raw) throws Invalid {
        byte[] src = raw.getBytes(StandardCharsets.UTF_8);
        try {
            return decode(src, STD, true);
        } catch (Invalid e) {
            try {
                return decode(src, URL, true);
            } catch (Invalid e2) {
                throw e;
            }
        }
    }

    /** Go's {@code Encoding.DecodeString}, as a loop of its {@code decodeQuantum}. */
    private static byte[] decode(byte[] src, byte[] map, boolean padded) throws Invalid {
        byte[] dst = new byte[src.length / 4 * 3 + 3];
        int n = 0;
        int si = 0;
        while (si < src.length) {
            int[] dbuf = new int[4];
            int dlen = 4;
            int j = 0;
            boolean done = false;
            for (; j < 4; j++) {
                if (si == src.length) {
                    if (j == 0) return Arrays.copyOf(dst, n);
                    if (j == 1 || padded) throw new Invalid(si - j);
                    dlen = j;
                    done = true;
                    break;
                }
                int in = src[si] & 0xff;
                si++;
                int out = map[in] & 0xff;
                if (out != 0xff) {
                    dbuf[j] = out;
                    continue;
                }
                if (in == '\n' || in == '\r') {
                    j--;
                    continue;
                }
                if (!padded || in != '=') throw new Invalid(si - 1);
                // The end, and padding.
                switch (j) {
                    case 0, 1 -> throw new Invalid(si - 1);
                    case 2 -> {
                        // "==" is expected; the first '=' is consumed.
                        while (si < src.length && (src[si] == '\n' || src[si] == '\r')) si++;
                        if (si == src.length) throw new Invalid(src.length);
                        if (src[si] != '=') throw new Invalid(si - 1);
                        si++;
                    }
                    default -> { }
                }
                while (si < src.length && (src[si] == '\n' || src[si] == '\r')) si++;
                if (si < src.length) throw new Invalid(si); // trailing garbage
                dlen = j;
                done = true;
                break;
            }
            int val = dbuf[0] << 18 | dbuf[1] << 12 | dbuf[2] << 6 | dbuf[3];
            byte b0 = (byte) (val >> 16), b1 = (byte) (val >> 8), b2 = (byte) val;
            if (dlen >= 2) dst[n] = b0;
            if (dlen >= 3) dst[n + 1] = b1;
            if (dlen >= 4) dst[n + 2] = b2;
            n += dlen - 1;
            if (done) break;
        }
        return Arrays.copyOf(dst, n);
    }
}
