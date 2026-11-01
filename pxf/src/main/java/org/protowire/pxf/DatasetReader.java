// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Streaming consumption for the {@code @dataset} directive (draft §3.4.4
 * "Streaming consumption"). Pulls bytes from an {@link InputStream} on
 * demand and yields one {@link Ast.DatasetRow} per {@link #next()} call,
 * with working-set memory bounded by the size of the largest single row.
 * Use it for datasets too large to materialize via {@link Pxf#unmarshal} /
 * {@link Pxf#unmarshalFull}.
 *
 * <p>Per the spec: a streaming API MUST enforce per-row arity on each row as
 * it is consumed (not deferred to end of input), and MUST yield rows in
 * source order. Both invariants fall out
 * of the implementation here: the row-boundary scanner produces one
 * {@code ( ... )} byte slice at a time, and {@link Parser#parseDatasetRow}
 * decodes it.
 *
 * <p>A DatasetReader is positioned at the first row after
 * {@link #DatasetReader(InputStream)} returns. Call {@link #next()} in a
 * loop until it returns {@code null}; the row sequence is exhausted at
 * that point. For documents containing multiple {@code @dataset}
 * directives, construct a second DatasetReader from {@link #tail()}.
 *
 * <p>A DatasetReader is NOT safe for concurrent use.
 */
public final class DatasetReader {

    /**
     * Cap on the byte budget for the {@code @dataset} header (leading
     * directives + {@code @dataset TYPE (col1, col2, ...)}). Real headers
     * are tiny; the cap exists to fail-fast on misuse — a DatasetReader
     * pointed at a multi-gigabyte document with no {@code @dataset}
     * directive shouldn't OOM trying to find one.
     */
    private static final int DEFAULT_HEADER_MAX_BYTES = 64 * 1024;

    /**
     * Chunk size for {@code InputStream.read} pulls. Larger reduces
     * syscall pressure; smaller bounds per-row peak buffer occupancy.
     */
    private static final int STREAM_PULL_SIZE = 4096;

    private final InputStream src;
    private byte[] pending = new byte[0];   // bytes pulled from src but not yet consumed
    private boolean srcEof;                  // src.read has returned -1
    private boolean finished;                // next() has returned null at least once
    private RuntimeException stickyError;

    private String type;
    private List<String> columns;
    private List<Ast.Directive> directives;
    private final UnmarshalOptions opts;
    /** The row type {@link #columns} last passed {@link BindRow#checkColumnPaths} against. */
    private com.google.protobuf.Descriptors.Descriptor pathsChecked;
    /** Document position of {@code pending[0]}, so a row reports where it is in its document. */
    private int line = 1;
    private int col = 1;
    private int offset;

    /**
     * Consume any leading directives ({@code @type}, {@code @<name>}, etc.)
     * and the {@code @dataset TYPE ( cols )} header, returning a reader
     * positioned at the first row.
     *
     * @throws NoSuchElementException if the input ends before any
     *                                {@code @dataset} directive is seen
     * @throws PxfException           on a malformed header
     * @throws IOException            on an underlying {@link InputStream}
     *                                read failure
     */
    public DatasetReader(InputStream src) throws IOException {
        this(src, UnmarshalOptions.defaults());
    }

    /**
     * As {@link #DatasetReader(InputStream)}, binding rows under {@code opts}
     * in {@link #scan}: a cell holding an Any block that opens with
     * {@code @type = "..."} needs its type resolver.
     */
    public DatasetReader(InputStream src, UnmarshalOptions opts) throws IOException {
        this.src = src;
        this.opts = opts;
        readHeader();
    }

    /** The row message type declared by the {@code @dataset} header. */
    public String type() { return type; }

    /** The column field names declared by the {@code @dataset} header, in source order. */
    public List<String> columns() { return columns; }

    /**
     * The side-channel directives ({@code @<name>} / {@code @entry} /
     * etc., NOT {@code @type} or {@code @dataset}) that appeared before the
     * {@code @dataset} header. Stable for the reader's lifetime.
     */
    public List<Ast.Directive> directives() { return directives; }

    /**
     * Returns an {@link InputStream} that yields the bytes the reader has
     * buffered but not consumed, followed by any remaining bytes from the
     * underlying source. Use it to chain a second DatasetReader for
     * documents containing multiple {@code @dataset} directives:
     *
     * <pre>{@code
     * var tr1 = new DatasetReader(src);
     * // ... iterate tr1.next() until null ...
     * var tr2 = new DatasetReader(tr1.tail());
     * }</pre>
     *
     * <p>MUST only be called after {@link #next()} has returned {@code null}.
     * Calling it earlier returns bytes the current reader still intends to
     * consume, which will desync the next reader.
     */
    public InputStream tail() {
        if (pending.length == 0) return src;
        return new SequenceInputStream(new ByteArrayInputStream(pending), src);
    }

    /**
     * Reads the next row. Returns {@code null} when the table's row
     * sequence is exhausted. After {@code null} (or any other error), all
     * subsequent calls return {@code null} or rethrow the sticky error.
     *
     * <p>The returned {@link Ast.DatasetRow}'s cells list is freshly
     * allocated; reading the next row does not invalidate it.
     */
    public Ast.DatasetRow next() throws IOException {
        if (stickyError != null) throw stickyError;
        if (finished) return null;
        for (;;) {
            try {
                int start = skipTrivia(pending);
                if (start >= 0 && start < pending.length) {
                    byte ch = pending[start];
                    if (ch == '(') {
                        int end = findMatchingParen(pending, start);
                        if (end >= 0) return takeRow(start, end);
                    } else if (ch == '@') {
                        // The row sequence ends at the next directive (draft
                        // §3.4.4 "Appending"); tail() reads on from it.
                        consume(start);
                        finished = true;
                        return null;
                    } else {
                        throw new PxfException(new Position(1, 1, start),
                                "pxf: expected '(' to start a @dataset row or a directive, got '" + (char) (ch & 0xff) + "'");
                    }
                }
                if (srcEof) {
                    if (start >= pending.length) {
                        consume(start);
                        finished = true;
                        return null;
                    }
                    // Truncated: the row sequence ends only at end of input
                    // or at a directive, never inside a construct.
                    int at = start >= 0 ? start : 0;
                    throw new PxfException(new Position(1, 1, at), "pxf: truncated @dataset row at end of input");
                }
            } catch (PxfException e) {
                // A scan error carries its offset into pending, a parse error
                // is already a document position.
                stickyError = e.position().line() == 1 && e.position().column() == 1
                        ? new PxfException(positionAt(e.position().offset()), stripPosition(e))
                        : e;
                throw stickyError;
            }
            pull(STREAM_PULL_SIZE);
        }
    }

    private Ast.DatasetRow takeRow(int start, int end) {
        Position at = positionAt(start);
        Ast.DatasetRow row = Parser.parseDatasetRow(sliceBytes(pending, start, end + 1), columns.size(), at);
        consume(end + 1);
        return row;
    }

    /** The document position of offset {@code at} in {@code pending}. */
    private Position positionAt(int at) {
        int l = line, c = col;
        for (int i = 0; i < at && i < pending.length; i++) {
            if (pending[i] == '\n') { l++; c = 1; } else { c++; }
        }
        return new Position(l, c, offset + at);
    }

    /** Drops the first {@code n} bytes of {@code pending}, keeping the position current. */
    private void consume(int n) {
        Position p = positionAt(n);
        line = p.line();
        col = p.column();
        offset = p.offset();
        pending = sliceBytes(pending, n, pending.length);
    }

    private static String stripPosition(PxfException e) {
        String prefix = e.position() + ": ";
        String m = e.getMessage();
        return m.startsWith(prefix) ? m.substring(prefix.length()) : m;
    }

    /**
     * Reads the next row and binds its cells to the fields of
     * {@code builder} by column name. Returns {@code false} when the
     * table's row sequence is exhausted. Cell-state semantics match
     * {@link BindRow#bindRow}: a {@code null} cell leaves the field
     * absent, an {@link Ast.NullVal} cell clears the field, any other
     * value sets the field.
     */
    public boolean scan(Message.Builder builder) throws IOException {
        Ast.DatasetRow row = next();
        if (row == null) return false;
        // The column paths depend on the row type and the header, never on
        // the row, so they are checked once per type.
        var desc = builder.getDescriptorForType();
        if (desc != pathsChecked) {
            BindRow.checkColumnPaths(desc, columns, row.pos());
            pathsChecked = desc;
        }
        BindRow.bind(opts, builder, columns, row);
        return true;
    }

    // -- internals --------------------------------------------------------

    private void readHeader() throws IOException {
        for (;;) {
            int headerEnd = scanHeaderEnd(pending);
            if (headerEnd >= 0) {
                // Parse the header prefix as a (rowless) PXF document.
                // Parser is happy with an @dataset directive that has no
                // rows yet, and validates everything we care about
                // (leading-directive shape, @type/@dataset conflict,
                // dotted columns, etc.).
                byte[] headerBytes = sliceBytes(pending, 0, headerEnd + 1);
                Ast.Document doc = Parser.parse(headerBytes);
                if (doc.datasets().isEmpty()) {
                    // Should not happen — scanHeaderEnd found an @dataset —
                    // but defensive.
                    throw new NoSuchElementException("pxf: no @dataset directive in stream");
                }
                Ast.DatasetDirective tbl = doc.datasets().get(0);
                this.type = tbl.type();
                this.columns = tbl.columns();
                this.directives = doc.directives();
                consume(headerEnd + 1);
                return;
            }
            if (srcEof) {
                throw new NoSuchElementException("pxf: no @dataset directive in stream");
            }
            if (pending.length >= DEFAULT_HEADER_MAX_BYTES) {
                throw new PxfException(Position.UNKNOWN,
                        "pxf: @dataset header exceeds " + DEFAULT_HEADER_MAX_BYTES + " bytes; "
                                + "check that the input begins with `@dataset TYPE (cols)`");
            }
            pull(STREAM_PULL_SIZE);
        }
    }

    private void pull(int n) throws IOException {
        if (srcEof) return;
        byte[] buf = new byte[n];
        int read = src.read(buf);
        if (read < 0) {
            srcEof = true;
            return;
        }
        if (read > 0) {
            byte[] grown = new byte[pending.length + read];
            System.arraycopy(pending, 0, grown, 0, pending.length);
            System.arraycopy(buf, 0, grown, pending.length, read);
            pending = grown;
        }
    }

    // -- byte-level row-boundary scanner --------------------------------
    //
    // Finds row boundaries with string / bytes-literal / line-comment /
    // block-comment awareness. Mirrors protowire-go's table_stream.go.

    /**
     * Search {@code input} for the first complete
     * {@code @dataset TYPE ( cols )} directive and return the index of the
     * {@code )} that closes its column list. Returns -1 if the input
     * ends before the header is complete (caller should pull more bytes).
     * Throws {@link PxfException} on malformed string/comment.
     */
    static int scanHeaderEnd(byte[] input) {
        int atIdx = findAtTable(input);
        if (atIdx < 0) return -1;
        int lparen = findNextChar(input, atIdx + "@dataset".length(), '(');
        if (lparen < 0) return -1;
        return findMatchingParen(input, lparen);
    }

    /**
     * Return the byte offset of the next {@code @dataset} keyword outside
     * strings/comments. The match must be followed by a non-identifier
     * byte so we don't false-match {@code @datasetau}. Returns -1 when not
     * found or when the input ends mid-construct.
     */
    static int findAtTable(byte[] input) {
        final byte[] needle = {'@', 'd', 'a', 't', 'a', 's', 'e', 't'};
        int i = 0;
        while (i < input.length) {
            int j = skipStringOrComment(input, i);
            if (j == NEED_MORE) return -1;
            if (j != i) { i = j; continue; }
            if (input[i] == '@' && i + needle.length <= input.length) {
                boolean match = true;
                for (int k = 1; k < needle.length; k++) {
                    if (input[i + k] != needle[k]) { match = false; break; }
                }
                if (match) {
                    int after = i + needle.length;
                    if (after == input.length) {
                        // `@dataset` followed by more bytes we haven't seen
                        // yet — be conservative.
                        return -1;
                    }
                    if (!isIdentPart(input[after])) {
                        return i;
                    }
                }
            }
            i++;
        }
        return -1;
    }

    /** Return the offset of the next {@code ch} outside strings/comments. */
    static int findNextChar(byte[] input, int startFrom, char ch) {
        int i = startFrom;
        while (i < input.length) {
            int j = skipStringOrComment(input, i);
            if (j == NEED_MORE) return -1;
            if (j != i) { i = j; continue; }
            if (input[i] == ch) return i;
            i++;
        }
        return -1;
    }

    /** Find the matching {@code )} for the {@code (} at {@code openIdx}. */
    static int findMatchingParen(byte[] input, int openIdx) {
        int depth = 1;
        int i = openIdx + 1;
        while (i < input.length) {
            int j = skipStringOrComment(input, i);
            if (j == NEED_MORE) return -1;
            if (j != i) { i = j; continue; }
            switch (input[i]) {
                case '(' -> { depth++; i++; }
                case ')' -> {
                    depth--;
                    if (depth == 0) return i;
                    i++;
                }
                default -> i++;
            }
        }
        return -1;
    }

    /**
     * Find the next {@code ( ... )} row in {@code input}, skipping
     * leading whitespace + comments. Returns an int[2] of [start, end]
     * (both inclusive of the parens) on success, or {@code null} when
     * either the input ran out mid-scan (caller pulls more) or the next
     * significant byte is not {@code (} (row sequence over).
     */
    static int[] findNextRow(byte[] input) {
        int i = skipTrivia(input);
        if (i < 0 || i >= input.length) return null;
        if (input[i] != '(') return null;
        int end = findMatchingParen(input, i);
        if (end < 0) return null;
        return new int[] {i, end};
    }

    /**
     * The offset of the first byte past whitespace and comments, or
     * {@link #NEED_MORE} when a comment runs past the end of {@code input}.
     * A string is not trivia: it stops the scan.
     */
    private static int skipTrivia(byte[] input) {
        int i = 0;
        while (i < input.length) {
            byte ch = input[i];
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') { i++; continue; }
            if (ch == '"' || ch == 'b') return i;
            int j = skipStringOrComment(input, i);
            if (j == NEED_MORE) return NEED_MORE;
            if (j == i) return i;
            i = j;
        }
        return i;
    }

    private static final int NEED_MORE = -1;

    /**
     * If {@code input[i]} opens a string / bytes-literal / line-comment /
     * block-comment, advance past it and return the new index. Return
     * {@code i} unchanged if it doesn't open one. Return {@link #NEED_MORE}
     * (= -1) if the construct is incomplete (caller treats as "pull
     * more bytes"). Throw on a malformed construct that can't be fixed
     * by more bytes (e.g. unterminated single-line string with a
     * literal newline).
     */
    static int skipStringOrComment(byte[] input, int i) {
        if (i >= input.length) return i;
        byte ch = input[i];
        if (ch == '"') {
            if (i + 2 < input.length && input[i + 1] == '"' && input[i + 2] == '"') {
                return skipTripleString(input, i);
            }
            return skipSimpleString(input, i);
        }
        if (ch == 'b' && i + 1 < input.length && input[i + 1] == '"') {
            return skipBytesLiteral(input, i);
        }
        if (ch == '#') return skipLineComment(input, i + 1);
        if (ch == '/' && i + 1 < input.length && input[i + 1] == '/') return skipLineComment(input, i + 2);
        if (ch == '/' && i + 1 < input.length && input[i + 1] == '*') return skipBlockComment(input, i + 2);
        return i;
    }

    private static int skipSimpleString(byte[] input, int i) {
        int j = i + 1;
        while (j < input.length) {
            byte b = input[j];
            if (b == '\\') {
                if (j + 1 >= input.length) return NEED_MORE;
                j += 2;
                continue;
            }
            if (b == '"') return j + 1;
            if (b == '\n') {
                throw new PxfException(new Position(1, 1, i), "pxf: unterminated string literal");
            }
            j++;
        }
        return NEED_MORE;
    }

    private static int skipTripleString(byte[] input, int i) {
        int j = i + 3;
        while (j + 2 < input.length) {
            if (input[j] == '"' && input[j + 1] == '"' && input[j + 2] == '"') return j + 3;
            j++;
        }
        return NEED_MORE;
    }

    private static int skipBytesLiteral(byte[] input, int i) {
        int j = i + 2; // past `b"`
        while (j < input.length) {
            byte b = input[j];
            if (b == '"') return j + 1;
            if (b == '\n') {
                throw new PxfException(new Position(1, 1, i), "pxf: unterminated bytes literal");
            }
            j++;
        }
        return NEED_MORE;
    }

    private static int skipLineComment(byte[] input, int from) {
        int j = from;
        while (j < input.length && input[j] != '\n') j++;
        return j;
    }

    private static int skipBlockComment(byte[] input, int from) {
        int j = from;
        while (j + 1 < input.length) {
            if (input[j] == '*' && input[j + 1] == '/') return j + 2;
            j++;
        }
        return NEED_MORE;
    }

    private static boolean isIdentPart(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9') || b == '_' || b == '.';
    }

    /** Slice a copy of {@code [from, to)} from {@code src}. */
    private static byte[] sliceBytes(byte[] src, int from, int to) {
        if (from < 0 || to > src.length || from > to) {
            throw new IllegalArgumentException("invalid slice [" + from + "," + to + ")");
        }
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, to - from);
        return out;
    }

    // Suppress "method may be static" warning — kept as instance for parity
    // with future state-aware variants (e.g. per-instance maxHeaderBytes).
    @SuppressWarnings("unused")
    private void noop() { Collections.emptyList(); }
}
