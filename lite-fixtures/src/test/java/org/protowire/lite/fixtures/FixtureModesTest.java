// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bench.v1.OrderPxfCodec;
import bench.v1.OrderSbeCodec;
import org.junit.jupiter.api.Test;
import settings.v1.SettingsPxfCodec;

/**
 * The three results protowire's {@code scripts/cross_envelope_check.sh}
 * expects from every port (protowire#244, #58), pinned here at the unit
 * level so the lite tier cannot drift from them between gate runs. The
 * documents and goldens are protowire's
 * {@code testdata/annotations/ok.pxf}, {@code missing-required.pxf},
 * {@code sbe-bench.pxf} and their {@code *.expected.hex}.
 */
class FixtureModesTest {
    static final String OK_PXF = "name = \"svc\"\n";
    static final String MISSING_REQUIRED_PXF = "retries = 5\n";
    static final String SBE_BENCH_PXF =
        "order_id = 1001\n" +
        "symbol = \"AAPL\"\n" +
        "price = 19150\n" +
        "quantity = 100\n" +
        "side = SIDE_SELL\n" +
        "active = true\n" +
        "weight = 0.85\n" +
        "score = 2.5\n" +
        "fills = [\n" +
        "  { fill_price = 19155\n    fill_qty = 25\n    fill_id = 5001 },\n" +
        "  { fill_price = 19160\n    fill_qty = 50\n    fill_id = 5002 }\n" +
        "]\n";

    static final String OK_EXPECTED_HEX = "0a0373766310031a0975732d656173742d312001";
    static final String SBE_BENCH_EXPECTED_HEX =
        "2a00010001000000e9030000000000004141504c00000000ce4a000000000000640000000101333333333333eb3f00002040"
        + "14000200d34a000000000000190000008913000000000000d84a000000000000320000008a13000000000000";

    @Test
    void okPxf_takesItsThreeDefaults() {
        // retries = 3, region = "us-east-1", verbose = true come from (pxf.default) = 1315.
        assertEquals(OK_EXPECTED_HEX, FixtureModes.hex(SettingsPxfCodec.unmarshal(OK_PXF).toByteArray()));
    }

    @Test
    void missingRequired_isRejected() {
        // `name` carries (pxf.required) = 1314 and is absent.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SettingsPxfCodec.unmarshal(MISSING_REQUIRED_PXF));
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    void sbeBench_marshalsToTheSharedBytes() {
        // Header: blockLength 42, templateId 1, schemaId 1, version 0 -- from (sbe.*) 1319-1322.
        assertEquals(SBE_BENCH_EXPECTED_HEX,
            FixtureModes.hex(OrderSbeCodec.marshal(OrderPxfCodec.unmarshal(SBE_BENCH_PXF))));
    }
}
