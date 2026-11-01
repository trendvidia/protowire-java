// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

/**
 * The fields of a {@code pxf.BigFloat}, which {@link Pb} maps a field of this
 * type to: the unsigned big-endian {@code mantissa} × 2^{@code exponent}, at
 * {@code prec} bits. The value is not computed here, but the decoder checks
 * {@code MaxBigFloatExponent} before handing the fields over (HARDENING.md
 * § Arbitrary-precision magnitudes).
 *
 * @param mantissa unsigned big-endian bytes
 * @param exponent the wire exponent
 * @param prec     precision in bits, the wire's {@code uint32} read as unsigned
 * @param negative the sign
 */
public record BigFloat(byte[] mantissa, int exponent, int prec, boolean negative) {}
