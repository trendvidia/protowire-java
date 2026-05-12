// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/**
 * A position in PXF source text.
 *
 * @param line   1-based line number
 * @param column 1-based column number
 * @param offset 0-based byte index into the input — useful for callers that
 *               need to slice raw bytes (e.g. directive-body extraction)
 */
public record Position(int line, int column, int offset) {
    public static final Position UNKNOWN = new Position(1, 1, 0);

    /** Back-compat constructor for callers that don't track byte offsets. */
    public Position(int line, int column) {
        this(line, column, 0);
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
