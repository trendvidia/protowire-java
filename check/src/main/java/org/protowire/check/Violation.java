// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

/**
 * One failed validation rule, as protowire-go's {@code check.Violation}.
 *
 * @param path    the dotted path of the offending field, in pxf presence
 *                tracking's spelling ({@code name}, {@code db.password},
 *                {@code keys[0]}); empty for a message-level rule
 * @param ruleId  the rule, namespaced by engine so reports from different
 *                validators stay distinguishable
 *                ({@code buf.validate.string.min_len})
 * @param message a human-readable description
 */
public record Violation(String path, String ruleId, String message) {
    public Violation {
        if (path == null) path = "";
        if (ruleId == null) ruleId = "";
        if (message == null) message = "";
    }
}
