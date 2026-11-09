// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.check;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** protowire-go's check/check_test.go, case for case (#205). */
class CheckTest {

    @Test
    void reportOk() {
        assertTrue(Report.ok(null));
        assertTrue(new Report(List.of()).ok());
        assertFalse(new Report(List.of(new Violation("a", "", "bad"))).ok());
    }

    @Test
    void errorMessage() {
        assertEquals("check: validation failed", new CheckException(null).getMessage());
        assertEquals("check: validation failed", new CheckException(new Report(List.of())).getMessage());
        assertEquals("check: port: value is required (rfc001.required)", new CheckException(new Report(List.of(
            new Violation("port", "rfc001.required", "value is required")))).getMessage());
        assertEquals("check: inconsistent", new CheckException(new Report(List.of(
            new Violation("", "", "inconsistent")))).getMessage());
        assertEquals("check: 2 violations; first: a: first", new CheckException(new Report(List.of(
            new Violation("a", "", "first"), new Violation("b", "", "second")))).getMessage());
    }

    @Test
    void aNullValidatorDoesNothing() {
        assertNull(Check.validate(null, "anything"));
    }

    @Test
    void anEngineErrorPropagatesAsItIs() {
        IllegalStateException boom = new IllegalStateException("engine down");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Check.validate(v -> { throw boom; }, "x"));
        assertSame(boom, e);
    }

    @Test
    void violationsFailWithTheReport() {
        Report rep = new Report(List.of(new Violation("name", "rule.x", "too short")));
        CheckException e = assertThrows(CheckException.class, () -> Check.validate(v -> rep, "x"));
        assertSame(rep, e.report());
        assertEquals("check: name: too short (rule.x)", e.getMessage());
    }

    @Test
    void aCleanPassReturnsTheReport() {
        Report clean = new Report(List.of());
        assertSame(clean, Check.validate(v -> clean, "x"));
        assertNull(Check.validate(v -> null, "x"));
    }
}
