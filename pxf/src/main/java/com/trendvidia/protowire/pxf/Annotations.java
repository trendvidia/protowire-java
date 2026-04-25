package com.trendvidia.protowire.pxf;

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

    static final int EXT_REQUIRED = 50000;
    static final int EXT_DEFAULT  = 50001;

    static boolean isRequired(FieldDescriptor fd) {
        return readBool(fd, EXT_REQUIRED);
    }

    static String getDefault(FieldDescriptor fd) {
        return readString(fd, EXT_DEFAULT);
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
