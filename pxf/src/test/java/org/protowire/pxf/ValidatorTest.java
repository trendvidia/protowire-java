// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.protowire.check.CheckException;
import org.protowire.check.Report;
import org.protowire.check.Validator;
import org.protowire.check.Violation;
import org.protowire.pxf.testproto.AllTypes;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UnmarshalOptions#withValidator} runs after a successful decode, as
 * protowire-go's {@code UnmarshalOptions.Validator} does (#205); the cases
 * of encoding/pxf/validator_test.go and dataset_scan_options_test.go's
 * TestDatasetScan_Validator.
 */
class ValidatorTest {

    private static final byte[] DOC = "string_field = \"x\"\nint32_field = 7".getBytes(StandardCharsets.UTF_8);
    private static final Report DENY = new Report(List.of(new Violation("string_field", "test.deny", "denied")));

    /** Records what it was given, and answers {@code report}. */
    private static final class Fake implements Validator {
        final Report report;
        final List<Object> seen = new ArrayList<>();
        Fake(Report report) { this.report = report; }
        @Override public Report validate(Object decoded) {
            seen.add(decoded);
            return report;
        }
    }

    @Test
    void unmarshalRunsTheValidatorOnTheDecodedMessage() {
        Fake v = new Fake(DENY);
        AllTypes.Builder b = AllTypes.newBuilder();
        CheckException e = assertThrows(CheckException.class, () -> UnmarshalOptions.defaults().withValidator(v).unmarshal(DOC, b));
        assertSame(DENY, e.report());
        assertEquals("check: string_field: denied (test.deny)", e.getMessage());
        assertEquals("x", ((AllTypes) v.seen.get(0)).getStringField());
        // The decoded message stays in the builder, as Go returns it beside the error.
        assertEquals(7, b.getInt32Field());
    }

    @Test
    void aCleanValidatorPasses() {
        AllTypes.Builder b = AllTypes.newBuilder();
        UnmarshalOptions.defaults().withValidator(new Fake(new Report(List.of()))).unmarshal(DOC, b);
        UnmarshalOptions.defaults().withValidator(new Fake(null)).unmarshal(DOC, b);
        assertEquals("x", b.getStringField());
    }

    @Test
    void anEngineErrorPropagatesAsItIs() {
        IllegalStateException boom = new IllegalStateException("engine down");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> UnmarshalOptions.defaults().withValidator(d -> { throw boom; }).unmarshal(DOC, AllTypes.newBuilder()));
        assertSame(boom, e);
    }

    @Test
    void unmarshalFullAttachesTheReport() {
        Report clean = new Report(List.of());
        Result r = UnmarshalOptions.defaults().withValidator(new Fake(clean)).unmarshalFull(DOC, AllTypes.newBuilder());
        assertSame(clean, r.report());
        assertNull(UnmarshalOptions.defaults().unmarshalFull(DOC, AllTypes.newBuilder()).report(), "no validator, no report");
        CheckException e = assertThrows(CheckException.class,
            () -> UnmarshalOptions.defaults().withValidator(new Fake(DENY)).unmarshalFull(DOC, AllTypes.newBuilder()));
        assertSame(DENY, e.report());
    }

    @Test
    void unmarshalByDescriptorRunsTheValidator() {
        Fake v = new Fake(DENY);
        assertThrows(CheckException.class, () -> UnmarshalOptions.defaults().withValidator(v).unmarshal(DOC, AllTypes.getDescriptor()));
        assertEquals("x", ((Message) v.seen.get(0)).getField(AllTypes.getDescriptor().findFieldByName("string_field")));
    }

    /** As protowire-go's TestDatasetScan_Validator: each row is validated, and a violation refuses it. */
    @Test
    void aDatasetRowIsValidated() throws Exception {
        String table = "@dataset test.v1.AllTypes ( string_field, int32_field )\n(\"x\", 1)\n";
        DatasetReader clean = new DatasetReader(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));
        clean.scan(DynamicMessage.newBuilder(AllTypes.getDescriptor()));
        DatasetReader denied = new DatasetReader(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)),
            UnmarshalOptions.defaults().withValidator(new Fake(DENY)));
        CheckException e = assertThrows(CheckException.class, () -> denied.scan(DynamicMessage.newBuilder(AllTypes.getDescriptor())));
        assertSame(DENY, e.report());
    }
}
