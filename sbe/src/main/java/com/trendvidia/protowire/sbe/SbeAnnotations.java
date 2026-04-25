package com.trendvidia.protowire.sbe;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/** Reads {@code (sbe.*)} options off file/message/field descriptors. */
final class SbeAnnotations {
    private SbeAnnotations() {}

    static final int EXT_SCHEMA_ID   = 50100;
    static final int EXT_VERSION     = 50101;
    static final int EXT_TEMPLATE_ID = 50200;
    static final int EXT_LENGTH      = 50300;
    static final int EXT_ENCODING    = 50301;

    static Integer fileUint32(FileDescriptor fd, int target) {
        return readUint32(fd.toProto().getOptions().toByteString(), target);
    }

    static Integer messageUint32(Descriptor md, int target) {
        return readUint32(md.toProto().getOptions().toByteString(), target);
    }

    static Integer fieldUint32(FieldDescriptor fd, int target) {
        return readUint32(fd.toProto().getOptions().toByteString(), target);
    }

    static String fieldString(FieldDescriptor fd, int target) {
        return readString(fd.toProto().getOptions().toByteString(), target);
    }

    private static Integer readUint32(ByteString opts, int target) {
        if (opts.isEmpty()) return null;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return null;
                int num = WireFormat.getTagFieldNumber(tag);
                if (num == target && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                    return in.readUInt32();
                }
                in.skipField(tag);
            }
        } catch (IOException e) { return null; }
    }

    private static String readString(ByteString opts, int target) {
        if (opts.isEmpty()) return null;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return null;
                int num = WireFormat.getTagFieldNumber(tag);
                if (num == target && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    return in.readStringRequireUtf8();
                }
                in.skipField(tag);
            }
        } catch (IOException e) { return null; }
    }
}
