// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
//
// The descriptor-free half of the SBE codec: the wire layout
// (MessageTemplate / FieldTemplate / GroupTemplate, kept under
// org.protowire.sbe so protowire-sbe's public names do not move), the
// zero-allocation View, the XML schema model, and the org.protowire.sbe.runtime
// reader/writer/codec that codegen-emitted <Message>SbeCodec companions drive.
// protobuf-javalite is compile-only: ByteString is the one protobuf type used,
// and whichever protobuf runtime the consumer has provides it.
plugins {
    `java-library`
}

dependencies {
    compileOnly("com.google.protobuf:protobuf-javalite:4.36.1")
}
