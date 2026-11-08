// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lists the {@code .pxf} files of a corpus vendored under the repository's
 * {@code testdata/}, protowire's {@code testdata/} byte for byte. A
 * driver walks the directory rather than a list of names, so a fixture added
 * by the next re-vendor runs without a test change. The verdict is the file
 * name: the corpora name every reject fixture {@code err-*}.
 */
final class CorpusFixtures {
    private CorpusFixtures() {}

    /**
     * The corpus directory on disk. Not a classpath lookup: {@code /any},
     * {@code /nulls} and the rest are also the generated packages' class
     * directories, and a directory lookup finds those first.
     */
    static Path dir(String dir) {
        String base = System.getProperty("protowire.testdata");
        assertNotNull(base, "protowire.testdata is unset; run through Gradle");
        Path root = Path.of(base, dir);
        assertTrue(Files.isDirectory(root), "missing vendored corpus " + root);
        return root;
    }

    /**
     * Fixtures the full tier does not meet yet, each against the open issue
     * that fixes it, keyed {@code <dir>/<file>}. {@link #meet} fails on a
     * listed fixture that meets its verdict, so the list only shrinks.
     */
    static final Map<String, String> KNOWN_GAPS = Map.of(
            "nulls/err-required-optional.pxf", "#189",
            "nulls/err-required-wrapper.pxf", "#189",
            "dataset/null-on-required-cell.pxf", "#189");

    /** Runs a fixture's verdict, or for a known gap checks that it still fails. */
    static void meet(String dir, String name, Executable verdict) throws Throwable {
        String issue = KNOWN_GAPS.get(dir + "/" + name);
        if (issue == null) {
            verdict.execute();
            return;
        }
        try {
            verdict.execute();
        } catch (Throwable stillAGap) {
            return;
        }
        fail(dir + "/" + name + " meets its verdict now: drop it from KNOWN_GAPS (" + issue + ")");
    }

    record Fixture(String name, byte[] data) {
        boolean rejects() { return name.startsWith("err-"); }
    }

    static List<Fixture> load(String dir) throws IOException {
        Path root = dir(dir);
        try (Stream<Path> files = Files.list(root)) {
            List<Fixture> out = files
                    .filter(p -> p.getFileName().toString().endsWith(".pxf"))
                    .sorted()
                    .map(p -> {
                        try {
                            return new Fixture(p.getFileName().toString(), Files.readAllBytes(p));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertFalse(out.isEmpty(), "empty corpus " + dir);
            return out;
        }
    }
}
