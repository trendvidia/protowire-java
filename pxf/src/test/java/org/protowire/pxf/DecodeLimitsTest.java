// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;
import org.protowire.pxf.testproto.BigNums;
import org.protowire.pxf.testproto.Tree;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft -01 § Mandatory Limits and HARDENING.md § Mandatory limits, per
 * call, for the descriptor-driven decoder (#79): each limit lowered
 * through {@link UnmarshalOptions#withLimits} rejects input the default
 * accepts, the same input under a limit above its size decodes (the
 * override lowers, it does not over-reject), and the default rejects a
 * 64 MiB + 1 input before reading a byte of it. The corpus rows this
 * mirrors: {@code pxf/oversize-2kib}, {@code giant-base64},
 * {@code many-elements-16}.
 */
class DecodeLimitsTest {

    private static UnmarshalOptions with(DecodeLimits l) { return UnmarshalOptions.defaults().withLimits(l); }

    private static AllTypes decode(String doc, UnmarshalOptions o) {
        AllTypes.Builder b = AllTypes.newBuilder();
        o.unmarshal(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static PxfException reject(String doc, UnmarshalOptions o, String needle) {
        PxfException e = assertThrows(PxfException.class, () -> decode(doc, o));
        assertTrue(e.getMessage().contains(needle), e.getMessage());
        return e;
    }

    @Test
    void limitsAreTheCrossPortDefaults() {
        assertEquals(64 << 20, Limits.MAX_MESSAGE_SIZE);
        assertEquals(Limits.MAX_MESSAGE_SIZE, Limits.MAX_BYTES_LITERAL_LENGTH);
        assertEquals(Limits.MAX_MESSAGE_SIZE, Limits.MAX_REPEATED_COUNT);
        DecodeLimits d = DecodeLimits.defaults();
        assertEquals(Limits.MAX_NESTING_DEPTH, d.maxNestingDepth());
        assertEquals(Limits.MAX_NUMERIC_LITERAL_DIGITS, d.maxNumericLiteralDigits());
        assertEquals(DecodeLimits.defaults(), UnmarshalOptions.defaults().limits(), "the three-argument constructor means defaults");
        assertThrows(IllegalArgumentException.class, () -> d.with("MaxVarintBytes", 1));
        assertThrows(IllegalArgumentException.class, () -> d.withMaxRepeatedCount(0));
        assertEquals(8, d.with("MaxRepeatedCount", 8).maxRepeatedCount());
    }

    // -- MaxMessageSize --------------------------------------------------------

    private static String twoKib() {
        // A 2 KiB document: one string field padded with comment lines.
        StringBuilder sb = new StringBuilder("string_field = \"x\"\n");
        while (sb.length() < 2048) sb.append("# padding padding padding padding padding padding padding\n");
        return sb.toString();
    }

    @Test
    void maxMessageSizeIsCheckedBeforeDecoding() {
        String doc = twoKib();
        UnmarshalOptions low = with(DecodeLimits.defaults().withMaxMessageSize(1024));
        PxfException e = reject(doc, low, "exceeds MaxMessageSize=1024");
        assertTrue(e.getMessage().startsWith("1:1:"), e.getMessage());
        assertEquals("x", decode(doc, with(DecodeLimits.defaults().withMaxMessageSize(4096))).getStringField());
        assertEquals("x", decode(doc, UnmarshalOptions.defaults()).getStringField());
    }

    @Test
    void defaultRejectsSixtyFourMebibytesPlusOne() {
        byte[] doc = new byte[Limits.MAX_MESSAGE_SIZE + 1];
        Arrays.fill(doc, (byte) ' ');
        PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc, AllTypes.newBuilder()));
        assertTrue(e.getMessage().contains("input of " + (Limits.MAX_MESSAGE_SIZE + 1) + " bytes exceeds MaxMessageSize=" + Limits.MAX_MESSAGE_SIZE), e.getMessage());
        // Exactly 64 MiB is within the limit (all whitespace: an empty message).
        byte[] atBound = new byte[Limits.MAX_MESSAGE_SIZE];
        Arrays.fill(atBound, (byte) ' ');
        Pxf.unmarshal(atBound, AllTypes.newBuilder());
    }

    // -- MaxBytesLiteralLength -------------------------------------------------

    @Test
    void bytesLiteralIsRefusedFromItsLengthBeforeDecoding() {
        byte[] raw = new byte[192];
        Arrays.fill(raw, (byte) 7);
        String doc = "bytes_field = b\"" + Base64.getEncoder().encodeToString(raw) + "\"";
        reject(doc, with(DecodeLimits.defaults().withMaxBytesLiteralLength(128)),
                "bytes literal decodes to more than MaxBytesLiteralLength=128 bytes");
        assertEquals(192, decode(doc, with(DecodeLimits.defaults().withMaxBytesLiteralLength(256))).getBytesField().size());
        assertEquals(192, decode(doc, with(DecodeLimits.defaults().withMaxBytesLiteralLength(192))).getBytesField().size());
    }

    // -- MaxRepeatedCount ------------------------------------------------------

    private static String list(int n) {
        StringBuilder sb = new StringBuilder("repeated_string = [");
        for (int i = 0; i < n; i++) sb.append(i > 0 ? ", " : "").append("\"e\"");
        return sb.append("]").toString();
    }

    private static String map(int n) {
        StringBuilder sb = new StringBuilder("string_map = {\n");
        for (int i = 0; i < n; i++) sb.append("  k").append(i).append(": \"v\"\n");
        return sb.append("}").toString();
    }

    @Test
    void repeatedCountIsRefusedBeforeTheNextElementIsAdded() {
        reject(list(16), with(DecodeLimits.defaults().withMaxRepeatedCount(8)),
                "repeated field \"repeated_string\" exceeds MaxRepeatedCount=8");
        assertEquals(16, decode(list(16), with(DecodeLimits.defaults().withMaxRepeatedCount(16))).getRepeatedStringCount());
        reject(map(16), with(DecodeLimits.defaults().withMaxRepeatedCount(8)),
                "map field \"string_map\" exceeds MaxRepeatedCount=8");
        assertEquals(16, decode(map(16), with(DecodeLimits.defaults().withMaxRepeatedCount(16))).getStringMapCount());
    }

    // -- the two limits that already existed, now per call ---------------------

    @Test
    void nestingDepthAndNumericDigitsArePerCallToo() {
        String nested = "child{".repeat(5) + "}".repeat(5);
        Tree.Builder t = Tree.newBuilder();
        PxfException e = assertThrows(PxfException.class,
                () -> with(DecodeLimits.defaults().withMaxNestingDepth(4)).unmarshal(nested.getBytes(StandardCharsets.UTF_8), t));
        assertTrue(e.getMessage().contains("MaxNestingDepth=4"), e.getMessage());
        with(DecodeLimits.defaults().withMaxNestingDepth(5)).unmarshal(nested.getBytes(StandardCharsets.UTF_8), Tree.newBuilder());

        String big = "big_int = " + "1".repeat(10);
        BigNums.Builder bn = BigNums.newBuilder();
        e = assertThrows(PxfException.class,
                () -> with(DecodeLimits.defaults().withMaxNumericLiteralDigits(9)).unmarshal(big.getBytes(StandardCharsets.UTF_8), bn));
        assertTrue(e.getMessage().contains("MaxNumericLiteralDigits=9"), e.getMessage());
        with(DecodeLimits.defaults().withMaxNumericLiteralDigits(10)).unmarshal(big.getBytes(StandardCharsets.UTF_8), BigNums.newBuilder());
    }
}
