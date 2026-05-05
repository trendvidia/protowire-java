package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoder-hardening invariants against adversarial input. Mirrors the
 * cross-port {@code testdata/adversarial} corpus: deep nesting, invalid
 * UTF-8, and surrogate escapes in {@code string} fields.
 *
 * <p>See {@code protowire/docs/HARDENING.md}.
 */
class HardeningTest {

    /** Generates a PXF document {@code child{child{...}}} of the requested depth. */
    private static String nestedBlocks(int depth) {
        StringBuilder sb = new StringBuilder(depth * 7 + 1);
        for (int i = 0; i < depth; i++) sb.append("child{");
        for (int i = 0; i < depth; i++) sb.append('}');
        return sb.toString();
    }

    @Test
    void parser_acceptsAtMaxDepth() {
        // 100 nested blocks is within MaxNestingDepth.
        Ast.Document doc = Parser.parse(nestedBlocks(100));
        assertEquals(1, doc.entries().size());
    }

    @Test
    void parser_rejectsBeyondMaxDepth() {
        // 101 exceeds MaxNestingDepth = 100.
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse(nestedBlocks(101)));
        assertTrue(e.getMessage().contains("max nesting depth"),
                "expected depth-limit error, got: " + e.getMessage());
    }

    @Test
    void parser_rejectsAdversarialDeepNesting200() {
        // Mirrors testdata/adversarial/pxf/deep-nesting-200.pxf shape.
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse(nestedBlocks(200)));
        assertTrue(e.getMessage().contains("max nesting depth"));
    }

    @Test
    void parser_rejectsAdversarialDeepNesting1000() {
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse(nestedBlocks(1000)));
        assertTrue(e.getMessage().contains("max nesting depth"));
    }

    @Test
    void parser_listsAlsoCountTowardDepth() {
        // [ [ [ ... ] ] ] of depth 101 — lists count toward MaxNestingDepth.
        StringBuilder sb = new StringBuilder("x = ");
        for (int i = 0; i < 101; i++) sb.append('[');
        for (int i = 0; i < 101; i++) sb.append(']');
        assertThrows(PxfException.class, () -> Parser.parse(sb.toString()));
    }

    // -- UTF-8 strictness on string literals --------------------------------

    @Test
    void lexer_rejectsInvalidUtf8FromHexEscape() {
        // \xFF\xFE is not valid UTF-8 — adversarial corpus invalid-utf8-string.pxf.
        Lexer lex = new Lexer("\"\\xFF\\xFE\"");
        assertEquals(TokenKind.ILLEGAL, lex.next().kind());
    }

    @Test
    void lexer_rejectsLoneHighSurrogateEscape() {
        // \uD800 is a lone surrogate — must not enter any string literal.
        Lexer lex = new Lexer("\"\\uD800\"");
        assertEquals(TokenKind.ILLEGAL, lex.next().kind());
    }

    @Test
    void lexer_acceptsValidMultiByteUtf8FromHexEscapes() {
        // \xC3\xA9 is the UTF-8 encoding of é — must still pass through.
        Lexer lex = new Lexer("\"\\xC3\\xA9\"");
        Token t = lex.next();
        assertEquals(TokenKind.STRING, t.kind());
        assertEquals("é", t.value());
    }
}
