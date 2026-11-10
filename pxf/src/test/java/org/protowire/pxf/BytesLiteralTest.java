// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.BytesValue;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code b"…"} literal takes the standard or the URL-safe base64 alphabet,
 * padded or not, as protowire-go's lexer does (draft §3.7, RFC 4648 §5;
 * #208). Java took only the standard alphabet, so it refused {@code b"-_8="}.
 * Each expected value is protowire-go v1.17.0's {@code Unmarshal} into a
 * BytesValue, and its {@code Parse} agrees.
 */
class BytesLiteralTest {

    private static final Map<String, String> GO = new LinkedHashMap<>();
    static {
        GO.put("b\"-_8=\"", "fbff");
        GO.put("b\"-_8\"", "fbff");
        GO.put("b\"aGk\"", "6869");
        GO.put("b\"aGk=\"", "6869");
        GO.put("b\"aG=k\"", "1:9: invalid base64 in bytes literal");
        GO.put("b\"!!\"", "1:9: invalid base64 in bytes literal");
        GO.put("b\"a\"", "1:9: invalid base64 in bytes literal");
        GO.put("b\"aGk==\"", "1:9: invalid base64 in bytes literal");
    }

    @Test
    void eachLiteralDecodesAsGoDecodesIt() {
        GO.forEach((lit, want) -> {
            String got;
            try {
                BytesValue.Builder b = BytesValue.newBuilder();
                Pxf.unmarshal(("value = " + lit).getBytes(java.nio.charset.StandardCharsets.UTF_8), b);
                got = HexFormat.of().formatHex(b.getValue().toByteArray());
            } catch (PxfException e) {
                got = e.getMessage();
            }
            assertEquals(want, got, lit);
        });
    }
}
