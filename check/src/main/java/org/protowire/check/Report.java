// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

import java.util.List;

/** The outcome of a validation pass: zero or more violations, as protowire-go's {@code check.Report}. */
public record Report(List<Violation> violations) {
    public Report {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** Whether the report holds no violations. */
    public boolean ok() { return violations.isEmpty(); }

    /** Whether {@code r} holds no violations; a null report holds none. */
    public static boolean ok(Report r) { return r == null || r.ok(); }
}
