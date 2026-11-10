// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import keyed.v1.Keyed.Node;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The comments that no entry follows (#200, protowire-go#279), where a
 * schema or a stream is involved: the schema-aware format keeps them when it
 * rewrites a keyed block, and {@link DatasetReader} gives a row the comments
 * {@link Parser} does. Each expected text is protowire-go v1.17.0's
 * {@code CanonicalizeKeyed} then {@code FormatDocument} of the document,
 * from Go's own #279 test.
 */
class ClosingCommentsSchemaTest {

    private static final Map<String, String> GO_KEYED = new LinkedHashMap<>();
    static {
        // A redundant key assignment that is dropped last in its block.
        GO_KEYED.put("@type keyed.v1.Node\n\nchildren {\n  greeting {\n    type = \"Label\"\n    # about the key\n    id = \"greeting\"\n  }\n  # end of children\n}\n",
                "@type keyed.v1.Node\n\nchildren {\n  greeting {\n    type = \"Label\"\n    # about the key\n  }\n  # end of children\n}\n");
        // The assignment form, rewritten as blocks.
        GO_KEYED.put("@type keyed.v1.Node\n\nchildren = {\n  greeting = {\n    type = \"Label\"\n    # end of greeting\n  }\n  # end of children\n}\n",
                "@type keyed.v1.Node\n\nchildren {\n  greeting {\n    type = \"Label\"\n    # end of greeting\n  }\n  # end of children\n}\n");
        // An anonymous list, rewritten as named blocks.
        GO_KEYED.put("@type keyed.v1.Node\n\nchildren = [\n  {\n    type = \"L\"\n    # about the key\n    id = \"g\"\n  },\n  {\n    id = \"h\"\n    # end of h\n  }\n]\n",
                "@type keyed.v1.Node\n\nchildren {\n  g {\n    type = \"L\"\n    # about the key\n  }\n  h {\n    # end of h\n  }\n}\n");
    }

    private static String formatKeyed(String doc) {
        return new String(Pxf.formatDocument(Parser.parse(doc.getBytes(StandardCharsets.UTF_8)), Node.getDescriptor()),
                StandardCharsets.UTF_8);
    }

    @Test
    void aKeyedRewriteKeepsThem() {
        GO_KEYED.forEach((doc, want) -> {
            assertEquals(want, formatKeyed(doc), doc);
            assertEquals(want, formatKeyed(want), "formatting is a fixed point: " + doc);
        });
    }

    /** Reads one byte per call, so a row and its comments arrive in pieces. */
    private static InputStream oneByte(String in) {
        return new FilterInputStream(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8))) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(1, len));
            }
        };
    }

    private static List<Ast.DatasetRow> stream(InputStream in) throws IOException {
        DatasetReader r = new DatasetReader(in);
        List<Ast.DatasetRow> rows = new ArrayList<>();
        for (Ast.DatasetRow row; (row = r.next()) != null; ) rows.add(row);
        return rows;
    }

    /** protowire-go's TestDatasetReader_RowComments bodies: the stream's rows are Parse's, comments and positions included. */
    @Test
    void aStreamedRowHoldsTheCommentsParseGivesIt() throws IOException {
        for (String body : List.of(
                "# first\n(1, 2)\n# between\n/* b */\n(3, 4)\n# end\n",
                "(1, # one\n 2)\n(3, 4) # after\n(5, 6)\n",
                "(1, 2)\n# about U\n@dataset U (z)\n(9)\n",
                // Read a byte at a time, a '/' ends the buffer before the
                // byte that makes it a comment.
                "(1, 2)\n// c\n(3, 4)\n")) {
            String doc = "@dataset T (a, b)\n" + body;
            List<Ast.DatasetRow> parsed = Parser.parse(doc.getBytes(StandardCharsets.UTF_8)).datasets().get(0).rows();
            assertEquals(parsed, stream(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8))), body);
            assertEquals(parsed, stream(oneByte(doc)), "one byte at a time: " + body);
        }
        List<Ast.DatasetRow> rows = stream(oneByte("@dataset T (a, b)\n(1, 2)\n# between\n(3, 4)\n"));
        assertEquals("# between", rows.get(1).leadingComments().get(0).text());
    }

    /**
     * The comments before the next directive stay for {@link DatasetReader#tail()}:
     * the tail is protowire-go v1.17.0's {@code Tail} on the same document,
     * read whole or a byte at a time. They used to be dropped.
     */
    @Test
    void theCommentsBeforeTheNextDirectiveGoToTheTail() throws IOException {
        String doc = "@dataset T (a, b)\n(1, 2)\n# about n\n@note\n@dataset U (z)\n(9)\n";
        for (InputStream in : List.of(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)), oneByte(doc))) {
            DatasetReader first = new DatasetReader(in);
            while (first.next() != null) { }
            assertEquals("\n# about n\n@note\n@dataset U (z)\n(9)\n",
                    new String(first.tail().readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
