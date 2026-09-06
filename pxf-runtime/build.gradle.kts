// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
//
// The descriptor-free half of the PXF codec: lexer, parser, AST, formatter,
// Result, and the PxfMeta / PxfRegistry contract that codegen-emitted
// companions (protoc-gen-pxf-java-meta) implement. No protobuf-java
// dependency, so :pxf-android can build on it against protobuf-javalite;
// :pxf layers the descriptor-driven decoder and encoder on top.
plugins {
    `java-library`
}

dependencies {
}
