// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/** A line/column position in PXF source text. Lines and columns are 1-based. */
public record Position(int line, int column) {
    public static final Position UNKNOWN = new Position(1, 1);

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
