// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.protowire.sbe.Codec.*;

/**
 * Zero-allocation reader. Holds an underlying byte buffer; field values are read at fixed offsets
 * directly from that buffer.
 */
public final class View {
    final byte[] data;
    final int blockStart;
    final int blockLength;
    final ViewSchema schema;
    private final ByteBuffer bb;

    View(byte[] data, int blockStart, int blockLength, ViewSchema schema) {
        this.data = data;
        this.blockStart = blockStart;
        this.blockLength = blockLength;
        this.schema = schema;
        this.bb = ByteBuffer.wrap(data).order(ORDER);
    }

    private FieldTemplate field(String name) {
        FieldTemplate ft = schema.fields.get(name);
        if (ft == null) throw new IllegalArgumentException("sbe: unknown field " + name);
        return ft;
    }

    public long intValue(String name) {
        FieldTemplate ft = field(name);
        int off = blockStart + ft.offset;
        return switch (ft.encoding) {
            case ENC_INT8  -> bb.get(off);
            case ENC_INT16 -> bb.getShort(off);
            case ENC_INT32 -> bb.getInt(off);
            case ENC_INT64 -> bb.getLong(off);
            default -> throw new IllegalStateException("not a signed integer: " + name);
        };
    }

    public long uintValue(String name) {
        FieldTemplate ft = field(name);
        int off = blockStart + ft.offset;
        return switch (ft.encoding) {
            case ENC_UINT8  -> bb.get(off) & 0xFFL;
            case ENC_UINT16 -> bb.getShort(off) & 0xFFFFL;
            case ENC_UINT32 -> bb.getInt(off) & 0xFFFFFFFFL;
            case ENC_UINT64 -> bb.getLong(off);
            default -> throw new IllegalStateException("not an unsigned integer: " + name);
        };
    }

    public double floatValue(String name) {
        FieldTemplate ft = field(name);
        int off = blockStart + ft.offset;
        return switch (ft.encoding) {
            case ENC_FLOAT  -> bb.getFloat(off);
            case ENC_DOUBLE -> bb.getDouble(off);
            default -> throw new IllegalStateException("not a float: " + name);
        };
    }

    public boolean boolValue(String name) {
        FieldTemplate ft = field(name);
        return bb.get(blockStart + ft.offset) != 0;
    }

    public String stringValue(String name) {
        FieldTemplate ft = field(name);
        int off = blockStart + ft.offset;
        int n = ft.size;
        while (n > 0 && data[off + n - 1] == 0) n--;
        return new String(data, off, n, StandardCharsets.UTF_8);
    }

    public byte[] bytesValue(String name) {
        FieldTemplate ft = field(name);
        int off = blockStart + ft.offset;
        byte[] out = new byte[ft.size];
        System.arraycopy(data, off, out, 0, ft.size);
        return out;
    }

    public View composite(String name) {
        FieldTemplate ft = field(name);
        if (ft.compositeView == null) throw new IllegalStateException("not a composite: " + name);
        return new View(data, blockStart + ft.offset, ft.size, ft.compositeView);
    }

    public GroupView group(String name) {
        int pos = blockStart + blockLength;
        for (ViewSchema.GroupInfo gi : schema.groupOrder) {
            int bl = Short.toUnsignedInt(bb.getShort(pos));
            int n  = Short.toUnsignedInt(bb.getShort(pos + 2));
            if (gi.name().equals(name)) return new GroupView(data, pos, bl, n, gi.schema());
            pos += GROUP_HEADER_SIZE + n * bl;
        }
        throw new IllegalArgumentException("sbe: unknown group " + name);
    }

    public static final class GroupView {
        private final byte[] data;
        private final int groupStart;
        private final int blockLength;
        private final int count;
        private final ViewSchema schema;

        GroupView(byte[] data, int groupStart, int blockLength, int count, ViewSchema schema) {
            this.data = data;
            this.groupStart = groupStart;
            this.blockLength = blockLength;
            this.count = count;
            this.schema = schema;
        }

        public int size() { return count; }

        public View entry(int i) {
            int start = groupStart + GROUP_HEADER_SIZE + i * blockLength;
            return new View(data, start, blockLength, schema);
        }
    }
}
