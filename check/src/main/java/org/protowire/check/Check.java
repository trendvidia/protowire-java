// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

/** The standard semantics every decoder applies a {@link Validator} with, as protowire-go's {@code check.Validate}. */
public final class Check {
    private Check() {}

    /**
     * Applies {@code v} to a decoded value: a null validator is a no-op and
     * returns null, an engine's exception propagates as it is, and a report
     * with violations fails with a {@link CheckException} carrying it.
     * Otherwise the report is returned, so a caller can attach it to its own
     * result.
     */
    public static Report validate(Validator v, Object decoded) {
        if (v == null) return null;
        Report r = v.validate(decoded);
        if (!Report.ok(r)) throw new CheckException(r);
        return r;
    }
}
