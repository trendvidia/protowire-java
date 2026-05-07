// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.envelope;

import org.protowire.envelope.v1.AppError;
import org.protowire.envelope.v1.Envelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopesTest {

    @Test
    void okEnvelopeQueries() {
        Envelope e = Envelopes.ok(200, "payload".getBytes());
        assertTrue(Envelopes.isOk(e));
        assertFalse(Envelopes.isTransportError(e));
        assertFalse(Envelopes.isAppError(e));
        assertEquals("", Envelopes.errorCode(e));
    }

    @Test
    void appErrorWithFieldsAndMeta() {
        AppError ae = Envelopes.appError("NOT_FOUND", "missing");
        ae = Envelopes.withField(ae, "user", "REQUIRED", "field is required");
        ae = Envelopes.withMeta(ae, "request_id", "abc");

        Envelope e = Envelope.newBuilder().setStatus(404).setError(ae).build();
        assertTrue(Envelopes.isAppError(e));
        assertEquals("NOT_FOUND", Envelopes.errorCode(e));
        assertEquals(1, Envelopes.fieldErrors(e).size());
        assertEquals("REQUIRED", Envelopes.fieldErrors(e).get("user").getCode());
        assertEquals("abc", e.getError().getMetadataMap().get("request_id"));
    }

    @Test
    void transportError() {
        Envelope e = Envelopes.transportErr("timeout");
        assertTrue(Envelopes.isTransportError(e));
        assertFalse(Envelopes.isOk(e));
    }
}
