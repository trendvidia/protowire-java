// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import duration.v1.Duration.Holder;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;
import settings.v1.SettingsOuterClass.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The spec corpus directories under {@code testdata/} that no other test
 * here drives ({@code annotations}, {@code duration}, {@code repeated}), and
 * the check that every vendored directory has a driver.
 */
class SpecCorpusTest {

    /** Every directory this tier runs, and the test class that runs it. */
    private static final Set<String> DRIVEN = Set.of(
            "annotations",  // here
            "any",          // AnyCorpusTest
            "bigfloat",     // BigFloatCorpusTest
            "blocks",       // RejectRulesCorpusTest
            "dataset",      // DatasetCorpusTest, RejectRulesCorpusTest
            "duplicates",   // RejectRulesCorpusTest
            "duration",     // here
            "keyed",        // KeyedFixtureTest
            "map-keys",     // MapKeysFixtureTest
            "nulls",        // RejectRulesCorpusTest
            "repeated",     // here
            "strings");     // RejectRulesCorpusTest

    @Test
    void everyVendoredDirectoryHasADriver() throws IOException {
        Path root = CorpusFixtures.dir("");
        Set<String> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(DRIVEN), dirs);
    }

    private static void verdicts(String dir, Descriptor d) throws Throwable {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load(dir)) {
            if (f.name().endsWith(".expected.pxf")) continue;
            CorpusFixtures.meet(dir, f.name(), () -> {
                if (f.rejects()) {
                    assertThrows(PxfException.class, () -> Pxf.unmarshal(f.data(), d), f.name());
                    assertThrows(PxfException.class,
                            () -> Pxf.unmarshalFull(f.data(), DynamicMessage.newBuilder(d)), f.name());
                } else {
                    Pxf.unmarshal(f.data(), d);
                    Pxf.unmarshalFull(f.data(), DynamicMessage.newBuilder(d));
                }
            });
        }
    }

    private static byte[] fixture(String dir, String name) throws IOException {
        return Files.readAllBytes(CorpusFixtures.dir(dir).resolve(name));
    }

    private static <B extends Message.Builder> B bind(String dir, String name, B b) throws IOException {
        Pxf.unmarshal(fixture(dir, name), b);
        return b;
    }

    // -- annotations: (pxf.required) = 1314, (pxf.default) = 1315 ------------

    @Test
    void annotations() throws Throwable {
        Settings.Builder b = Settings.newBuilder();
        Pxf.unmarshalFull(fixture("annotations", "ok.pxf"), b);
        String hex = new String(fixture("annotations", "ok.expected.hex"), StandardCharsets.US_ASCII).trim();
        assertArrayEquals(HexFormat.of().parseHex(hex), b.build().toByteArray(), "ok.pxf");

        assertThrows(PxfException.class,
                () -> Pxf.unmarshalFull(fixture("annotations", "missing-required.pxf"), Settings.newBuilder()));
    }

    // -- duration: signed literals ------------------------------------------

    @Test
    void duration() throws Throwable {
        verdicts("duration", Holder.getDescriptor());
    }

    @Test
    void durationsBindWithOneSign() throws IOException {
        record Want(String file, long seconds, int nanos) {}
        for (Want w : List.of(
                new Want("negative-seconds.pxf", -30, 0),
                new Want("negative-fractional.pxf", -1, -500_000_000),
                new Want("negative-multi-segment.pxf", -5400, 0),
                new Want("negative-zero.pxf", 0, 0))) {
            com.google.protobuf.Duration d = bind("duration", w.file(), Holder.newBuilder()).getD();
            assertEquals(w.seconds(), d.getSeconds(), w.file());
            assertEquals(w.nanos(), d.getNanos(), w.file());
        }
    }

    @Test
    void aNegativeDurationReEncodesWithOneLeadingSign() throws IOException {
        String expected = new String(fixture("duration", "roundtrip-negative.expected.pxf"), StandardCharsets.UTF_8);
        Holder h = bind("duration", "roundtrip-negative.pxf", Holder.newBuilder()).build();
        String out = new String(MarshalOptions.defaults().withTypeUrl("duration.v1.Holder").marshal(h),
                StandardCharsets.UTF_8);
        assertEquals(expected, out);
    }

    // -- repeated: a repeated field is written with a list ------------------

    @Test
    void repeated() throws Throwable {
        verdicts("repeated", AllTypes.getDescriptor());
    }

    @Test
    void listBindingsConcatenate() throws IOException {
        AllTypes m = bind("repeated", "concat-lists.pxf", AllTypes.newBuilder()).build();
        assertEquals(List.of("a", "b", "c"), m.getRepeatedStringList());
        assertEquals(List.of("n1", "n2"), m.getRepeatedNestedList().stream().map(n -> n.getName()).toList());
    }
}
