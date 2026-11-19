// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Format;
import org.protowire.pxf.android.LiteWireReader;
import wiretype.v1.All;
import wiretype.v1.AllPxfMeta;
import wiretype.v1.Inner;

/**
 * A tag's wire type on the lite tier, against protobuf-javalite (#257,
 * trendvidia/protowire#614): protowire-go's {@code wire_type_test.go} ported to
 * {@link LiteWireReader}, the lite tier's PB decoder.
 *
 * <p>A PB decoder rejects wire type 6 or 7 on any field, and handles a known
 * field whose wire type does not match its type as an unknown field, skipped
 * by its wire type with the field left as it was. The oracle is the generated
 * lite parser for {@code wire_type.proto}, protowire-go's {@code conf.proto}.
 * Every field is sent one record of every wire type, well formed and cut
 * short, at the top level and inside each message and map-entry field. Each
 * input must get protobuf-javalite's verdict, and on accept the same values:
 * the oracle's message, rebuilt without its unknown fields and re-encoded,
 * must read here to the same document as the input.
 */
class WireTypeLiteTest {

    private static final int VARINT = 0, I64 = 1, LEN = 2, SGROUP = 3, EGROUP = 4, I32 = 5;

    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static byte[] tag(int num, int type) {
        return varint(((long) num << 3) | type);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] unhex(String h) {
        return HexFormat.of().parseHex(h);
    }

    private static String hex(byte[] v) {
        return HexFormat.of().formatHex(v);
    }

    /** Single records for field num: every wire type, each with a well-formed value and a value cut short. */
    private static List<byte[]> wireRecords(int num) {
        return List.of(
                cat(tag(num, VARINT), b(0x01)),
                cat(tag(num, VARINT), b(0x80)),                   // cut short
                cat(tag(num, I64), b(1, 0, 0, 0, 0, 0, 0, 0)),
                cat(tag(num, I64), b(1, 0, 0)),                   // cut short
                cat(tag(num, LEN), b(0x00)),
                cat(tag(num, LEN), b(0x01, 0x01)),
                cat(tag(num, LEN), b(0x01)),                      // cut short
                cat(tag(num, SGROUP), tag(num, EGROUP)),
                tag(num, SGROUP),                                 // never ended
                tag(num, EGROUP),                                 // ends nothing
                cat(tag(num, I32), b(1, 0, 0, 0)),
                cat(tag(num, I32), b(1, 0)),                      // cut short
                cat(tag(num, 6), b(0x01)),
                cat(tag(num, 7), b(0x01)));
    }

    /**
     * Every record for every field of All, alone and carried inside each
     * message field (12, 13, 22: Inner's fields 1, 2 and an unknown 3) and map
     * field (18, 19, 25: key 1, value 2, unknown 3).
     */
    private static List<byte[]> wireTypeInputs() {
        List<byte[]> out = new ArrayList<>();
        for (int num = 1; num <= 25; num++) out.addAll(wireRecords(num));
        for (int outer : new int[] {12, 13, 18, 19, 22, 25}) {
            for (int inner = 1; inner <= 3; inner++) {
                for (byte[] rec : wireRecords(inner)) {
                    out.add(cat(tag(outer, LEN), varint(rec.length), rec));
                }
            }
        }
        // A matching record after a mismatched one: the mismatch leaves the
        // field as it was, and the match still sets it.
        out.add(unhex("0a0161" + "0801"));      // s: "a", then s as a varint
        out.add(unhex("1007" + "1501000000"));  // i64: 7, then i64 as fixed32
        out.add(unhex("720101" + "7001"));      // nums packed [1], then nums as a varint
        out.add(unhex("7001" + "7501000000"));  // nums [1], then nums as fixed32
        return out;
    }

    private static Inner known(Inner i) {
        return Inner.newBuilder().setName(i.getName()).setN(i.getN()).build();
    }

    /**
     * The oracle's message rebuilt from its fields, so without the unknown
     * fields protobuf-javalite keeps (it has no option to drop them on read).
     */
    private static All known(All o) {
        All.Builder b = All.newBuilder()
                .setS(o.getS()).setI64(o.getI64()).setI32(o.getI32()).setU64(o.getU64()).setU32(o.getU32())
                .setZ64(o.getZ64()).setZ32(o.getZ32()).setB(o.getB()).setF64(o.getF64()).setF32(o.getF32())
                .setBuf(o.getBuf());
        if (o.hasInner()) b.setInner(known(o.getInner()));
        for (Inner i : o.getListList()) b.addList(known(i));
        b.addAllNums(o.getNumsList()).addAllZigs(o.getZigsList()).addAllReals(o.getRealsList())
                .addAllStrs(o.getStrsList()).putAllM(o.getMMap());
        o.getMmMap().forEach((k, v) -> b.putMm(k, known(v)));
        switch (o.getChoiceCase()) {
            case A -> b.setA(o.getA());
            case C -> b.setC(o.getC());
            case D -> b.setD(known(o.getD()));
            default -> { }
        }
        b.addAllFlags(o.getFlagsList()).addAllNegs(o.getNegsList()).putAllBm(o.getBmMap());
        return b.build();
    }

    private static String read(byte[] wire) {
        return Format.formatDocument(LiteWireReader.toAst(wire, AllPxfMeta.INSTANCE));
    }

    @Test
    void wireTypeMatchesProtobufLite() throws Exception {
        // The rebuild drops what the oracle kept as unknown.
        All withUnknown = All.parseFrom(unhex("c80601" + "0a0161"));
        assertTrue(withUnknown.toByteArray().length > 3, "the oracle keeps field 105");
        assertEquals("0a0161", hex(known(withUnknown).toByteArray()), "the rebuild drops it");

        List<byte[]> inputs = wireTypeInputs();
        int accepted = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();
        for (byte[] in : inputs) {
            All want;
            try {
                want = All.parseFrom(in);
            } catch (InvalidProtocolBufferException e) {
                want = null;
            }
            String got;
            try {
                got = read(in);
            } catch (RuntimeException e) {
                got = null;
                if (want != null) {
                    failures.add(hex(in) + ": protobuf-javalite accepts it, the lite reader says " + e.getMessage());
                }
            }
            if (want == null) {
                rejected++;
                if (got != null) failures.add(hex(in) + ": protobuf-javalite rejects it, the lite reader accepts it");
                continue;
            }
            accepted++;
            if (got == null) continue;
            String w = read(known(want).toByteArray());
            if (!got.equals(w)) {
                failures.add(hex(in) + ": values differ from protobuf-javalite's:\n got: " + got + "\nwant: " + w);
            }
        }
        // A positive marker that both verdicts were exercised, and not only one.
        System.out.println(inputs.size() + " inputs: protobuf-javalite accepts " + accepted + ", rejects " + rejected
                + "; " + failures.size() + " differ");
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + inputs.size() + " inputs differ from protobuf-javalite:\n"
                    + String.join("\n", failures));
        }
        assertEquals(606, inputs.size());
        assertTrue(accepted > 100, "accepted " + accepted);
        assertTrue(rejected > 100, "rejected " + rejected);
    }
}
