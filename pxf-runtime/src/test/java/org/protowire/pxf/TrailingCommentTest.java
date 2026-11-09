// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A comment after an entry on the same line is that entry's trailing
 * comment, and formatting writes it back in place (#193). Each expected text
 * is protowire-go v1.17.0's FormatDocument of the document.
 */
class TrailingCommentTest {

    private static final Map<String, String> GO = new LinkedHashMap<>();
    static {
        // Mid-body: it used to become c's leading comment.
        GO.put("a = 1\nb = 1 # tc\nc = 2\n", "a = 1\nb = 1 # tc\nc = 2\n");
        // Last in a block, and last in the document: it used to be lost.
        GO.put("m {\n  x = 1 # tc\n}\n", "m {\n  x = 1 # tc\n}\n");
        GO.put("a = 1 # tc\n", "a = 1 # tc\n");
        GO.put("m = {\n  k: 1 # tc\n  j: 2\n}\n", "m = {\n  k: 1 # tc\n  j: 2\n}\n");
        GO.put("p {\n  @type = \"x.v1.T\" # tc\n  a = 1\n}\n", "p {\n  @type = \"x.v1.T\" # tc\n  a = 1\n}\n");
        // After a list or a block value, it follows the closing bracket.
        GO.put("l = [1, 2] # tc\nn = 3\n", "l = [\n  1,\n  2\n] # tc\nn = 3\n");
        GO.put("v = { a = 1 } # tc\n", "v = {\n  a = 1\n} # tc\n");
        GO.put("a = 1 // slash\nb = 2 /* block */\n", "a = 1 // slash\nb = 2 /* block */\n");
        // A comment on its own line still leads the next entry.
        GO.put("a = 1\n# leading\nb = 2\n", "a = 1\n# leading\nb = 2\n");
    }

    @Test
    void formattingKeepsItWhereGoKeepsIt() {
        GO.forEach((doc, want) ->
                assertEquals(want, Format.formatDocument(Parser.parse(doc.getBytes(StandardCharsets.UTF_8))), doc));
    }

    @Test
    void itIsTheEntrysTrailingComment() {
        Ast.Document d = Parser.parse("a = 1\nb = 1 # tc\nc = 2\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("# tc", ((Ast.Assignment) d.entries().get(1)).trailingComment());
        assertEquals(0, ((Ast.Assignment) d.entries().get(2)).leadingComments().size());
    }
}
