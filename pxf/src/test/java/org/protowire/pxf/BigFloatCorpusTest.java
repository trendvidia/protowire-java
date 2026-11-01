// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import bf.v1.Bigfloat.H;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/bigfloat/} corpus (#104), vendored
 * under {@code src/test/resources/corpus/bigfloat/} from
 * trendvidia/protowire 234cf29. Its expected values come from exact rational
 * arithmetic in {@code generate.py}, independently of any port.
 */
class BigFloatCorpusTest {

    private static byte[] binpb(String name) throws IOException {
        try (InputStream in = BigFloatCorpusTest.class.getResourceAsStream("/corpus/bigfloat/" + name + ".binpb")) {
            assertNotNull(in, "missing " + name + ".binpb");
            return in.readAllBytes();
        }
    }

    private static String stem(CorpusFixtures.Fixture f) {
        return f.name().substring(0, f.name().length() - ".pxf".length());
    }

    private static String lastLine(byte[] pxf) {
        String[] lines = new String(pxf, StandardCharsets.UTF_8).strip().split("\n");
        return lines[lines.length - 1];
    }

    @Test
    void everyCaseMeetsItsVerdict() throws Exception {
        List<CorpusFixtures.Fixture> all = CorpusFixtures.load("bigfloat");
        assertEquals(35, all.size());
        int enc = 0, dec = 0, err = 0;
        for (CorpusFixtures.Fixture f : all) {
            String name = stem(f);
            if (name.startsWith("enc-")) {
                enc++;
                H h = H.parseFrom(binpb(name));
                String out = new String(Pxf.marshal(h), StandardCharsets.UTF_8).strip();
                assertEquals(lastLine(f.data()), out, name);
            } else if (name.startsWith("dec-")) {
                dec++;
                H.Builder b = H.newBuilder();
                Pxf.unmarshal(f.data(), b);
                assertEquals(HexFormat.of().formatHex(binpb(name)),
                        HexFormat.of().formatHex(b.build().toByteArray()), name);
            } else {
                err++;
                assertThrows(PxfException.class, () -> Pxf.unmarshal(f.data(), H.newBuilder()), name);
            }
        }
        assertEquals(13, enc);
        assertEquals(18, dec);
        assertEquals(4, err);
    }

    /** What the suffix is for: value and prec survive a text round trip. */
    @Test
    void everyEncodedValueReadsBack() throws Exception {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("bigfloat")) {
            String name = stem(f);
            if (!name.startsWith("enc-")) continue;
            H first = roundTrip(H.parseFrom(binpb(name)));
            assertEquals(first, roundTrip(first), name);
        }
    }

    private static H roundTrip(H h) {
        H.Builder b = H.newBuilder();
        Pxf.unmarshal(Pxf.marshal(h), b);
        return b.build();
    }

    @Test
    void aSuffixElsewhereNamesTheField() throws IOException {
        byte[] doc = CorpusFixtures.load("bigfloat").stream()
                .filter(f -> f.name().equals("err-suffix-on-int32.pxf")).findFirst().orElseThrow().data();
        PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc, H.newBuilder()));
        assertTrue(e.getMessage().contains("not on field \"count\""), e.getMessage());
    }

    /** A random sample beyond the corpus: the 35,600-value claim of #406, at this port's own sizes. */
    @Test
    void randomValuesRoundTripAtTheirPrecision() {
        java.util.Random r = new java.util.Random(406);
        for (int i = 0; i < 2000; i++) {
            int prec = 24 + r.nextInt(600);
            BigInteger m = new BigInteger(prec, r).setBit(prec - 1);
            int exp = r.nextInt(8000) - 4000;
            String lit = BigFloats.format(m, exp, prec, false, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
            BigFloats.Value v = BigFloats.parse(lit, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
            assertEquals(new BigFloats.Value(m, exp, prec, false), v, lit);
        }
    }

    @Test
    void pow2DigitsAgreesWithTheBigInteger() {
        for (int p : new int[] {1, 3, 4, 53, 64, 256, 1000, 13603, 13604}) {
            assertEquals(BigInteger.ONE.shiftLeft(p).toString().length(), BigFloats.pow2Digits(p), "p=" + p);
        }
        // float64 is one digit high here (protowire-go's TestPow2Digits). The
        // expected value is floor(p × log10(2)) + 1 at 60 decimal digits.
        assertEquals(44240665, BigFloats.pow2Digits(146964308));
    }

    @Test
    void aHugeExponentIsRefusedBeforeItIsComputed() {
        assertThrows(PxfException.class, () -> Pxf.unmarshal("bf = 1e646456992".getBytes(), H.newBuilder()));
        assertThrows(PxfException.class, () -> Pxf.unmarshal("bf = 1e-99999999999999999999999".getBytes(), H.newBuilder()));
        H.Builder zero = H.newBuilder();
        Pxf.unmarshal("bf = 0e99999999999999999999999".getBytes(), zero);
        assertEquals(0, zero.getBf().getPrec());
    }

    @Test
    void aProducerRefusesAValuePastTheBound() throws InvalidProtocolBufferException {
        H h = H.newBuilder().setBf(H.getDefaultInstance().getBf().toBuilder()
                .setMantissa(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
                .setExponent(Integer.MAX_VALUE).setPrec(64)).build();
        PxfException e = assertThrows(PxfException.class, () -> Pxf.marshal(h));
        assertTrue(e.getMessage().contains("MaxBigFloatExponent"), e.getMessage());
    }
}
