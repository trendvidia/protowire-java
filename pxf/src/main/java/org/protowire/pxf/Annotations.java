// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * Reads {@code (pxf.required)} and {@code (pxf.default)} field options.
 *
 * <p>The field options bytes are accessed via {@code FieldDescriptor#getOptions().toByteString()}.
 * We scan that buffer directly so the same code works whether the descriptor was produced by
 * protoc with linked extensions, or from a raw FileDescriptorSet.
 */
final class Annotations {
    private Annotations() {}

    static final int EXT_REQUIRED = 1314;
    static final int EXT_DEFAULT  = 1315;
    static final int EXT_KEY      = 1316;

    static boolean isRequired(FieldDescriptor fd) {
        return readBool(fd, EXT_REQUIRED);
    }

    /** The {@code (pxf.default)} literal, or null when the option is not set. */
    static String getDefault(FieldDescriptor fd) {
        return readString(fd, EXT_DEFAULT);
    }

    /** The {@code (pxf.key)} field name, or null when the option is not set. */
    static String getKey(FieldDescriptor fd) {
        return readString(fd, EXT_KEY);
    }

    /**
     * The key field of a keyed repeated field: the singular string field
     * of {@code fd}'s element message that its {@code (pxf.key)} names
     * (draft -01 §3.13). Null when {@code fd} carries no {@code (pxf.key)}
     * or its placement is invalid — {@code fd} is not a repeated
     * message-typed field, the named field does not exist, or it is not a
     * singular string field; {@link SchemaValidator} reports those as
     * violations.
     */
    static FieldDescriptor keyField(FieldDescriptor fd) {
        if (fd == null || !fd.isRepeated() || fd.isMapField() || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return null;
        String name = getKey(fd);
        if (name == null) return null;
        FieldDescriptor kf = fd.getMessageType().findFieldByName(name);
        if (kf == null || kf.isRepeated() || kf.getType() != FieldDescriptor.Type.STRING) return null;
        return kf;
    }

    private static boolean readBool(FieldDescriptor fd, int target) {
        ByteString opts = fd.getOptions().toByteString();
        if (opts.isEmpty()) return false;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return false;
                int num = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                if (num == target && wire == WireFormat.WIRETYPE_VARINT) {
                    return in.readBool();
                }
                in.skipField(tag);
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static String readString(FieldDescriptor fd, int target) {
        ByteString opts = fd.getOptions().toByteString();
        if (opts.isEmpty()) return null;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return null;
                int num = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                if (num == target && wire == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    return in.readStringRequireUtf8();
                }
                in.skipField(tag);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
