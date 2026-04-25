package com.trendvidia.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

/** Configurable PXF decoding options. */
public record UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown) {

    public static UnmarshalOptions defaults() { return new UnmarshalOptions(null, false); }

    public UnmarshalOptions withTypeResolver(TypeResolver r) { return new UnmarshalOptions(r, discardUnknown); }
    public UnmarshalOptions withDiscardUnknown(boolean v)    { return new UnmarshalOptions(typeResolver, v); }

    public DynamicMessage unmarshal(byte[] data, Descriptor desc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        unmarshal(data, b);
        return (DynamicMessage) b.build();
    }

    public void unmarshal(byte[] data, Message.Builder b) {
        new FastDecoder(data, this, /* trackPresence= */ false).decode(b);
    }

    public Result unmarshalFull(byte[] data, Message.Builder b) {
        FastDecoder d = new FastDecoder(data, this, /* trackPresence= */ true);
        d.decode(b);
        return d.result();
    }
}
