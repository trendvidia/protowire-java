// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;

/**
 * Top-level entry points for PXF encoding/decoding.
 *
 * <p>Mirrors the Go module's {@code pxf.Marshal}, {@code pxf.Unmarshal}, {@code pxf.Parse},
 * {@code pxf.UnmarshalFull}.
 */
public final class Pxf {
    private Pxf() {}

    // -- AST path ----------------------------------------------------------

    public static Ast.Document parse(byte[] data) { return Parser.parse(data); }
    public static Ast.Document parse(String data) { return Parser.parse(data); }

    public static byte[] formatDocument(Ast.Document doc) {
        return Format.formatDocument(doc).getBytes(StandardCharsets.UTF_8);
    }

    // -- decode (fused fast path) -----------------------------------------

    public static byte[] marshal(Message msg) { return new Encoder(MarshalOptions.defaults()).encode(msg); }

    public static DynamicMessage unmarshal(byte[] data, Descriptor desc) {
        return UnmarshalOptions.defaults().unmarshal(data, desc);
    }

    public static void unmarshal(byte[] data, Message.Builder builder) {
        UnmarshalOptions.defaults().unmarshal(data, builder);
    }

    public static Result unmarshalFull(byte[] data, Message.Builder builder) {
        return UnmarshalOptions.defaults().unmarshalFull(data, builder);
    }

    public static Result unmarshalFull(byte[] data, Descriptor desc, Message.Builder builder) {
        return UnmarshalOptions.defaults().unmarshalFull(data, builder);
    }
}
