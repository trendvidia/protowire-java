// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

/**
 * The decode failure a {@link Validator} that reports violations produces,
 * as protowire-go's {@code check.Error}, with the full {@link Report}. Its
 * message names the first violation, word for word as Go's
 * {@code Error()} does: {@code check: port: value is required
 * (rfc001.required)}, or {@code check: 2 violations; first: a: first}.
 */
public final class CheckException extends RuntimeException {
    private final transient Report report;

    public CheckException(Report report) {
        super(message(report));
        this.report = report;
    }

    /** The report, with every violation. */
    public Report report() { return report; }

    private static String message(Report r) {
        int n = r == null ? 0 : r.violations().size();
        if (n == 0) return "check: validation failed";
        Violation v = r.violations().get(0);
        String msg = v.message();
        if (!v.path().isEmpty()) msg = v.path() + ": " + msg;
        if (!v.ruleId().isEmpty()) msg += " (" + v.ruleId() + ")";
        return n == 1 ? "check: " + msg : "check: " + n + " violations; first: " + msg;
    }
}
