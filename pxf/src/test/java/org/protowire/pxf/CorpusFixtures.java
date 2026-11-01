// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Lists the {@code .pxf} files of a corpus vendored under
 * {@code src/test/resources/corpus/} from trendvidia/protowire {@code testdata/}. A
 * driver walks the directory rather than a list of names, so a fixture added
 * by the next re-vendor runs without a test change. The verdict is the file
 * name: the corpora name every reject fixture {@code err-*}.
 */
final class CorpusFixtures {
    private CorpusFixtures() {}

    record Fixture(String name, byte[] data) {
        boolean rejects() { return name.startsWith("err-"); }
    }

    static List<Fixture> load(String dir) throws IOException {
        URL url = CorpusFixtures.class.getResource("/corpus/" + dir);
        assertNotNull(url, "missing vendored corpus " + dir);
        Path root;
        try {
            root = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
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
