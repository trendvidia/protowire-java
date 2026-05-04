plugins {
    `java-library`
}

// Descriptor-free PXF primitives: lexer, parser, AST, formatter, position
// tracking, exception type. No dependency on protobuf-java, protobuf-javalite,
// or the proto-annotations module — those live one layer up in :pxf, which
// adds the descriptor-coupled encode/decode paths on top of these pieces.
//
// This module exists so the upcoming :pxf-android (lite-runtime) variant
// can share the same lexer/parser without pulling in full-runtime protobuf.

dependencies {
    // Intentionally empty: pure JDK only. JUnit is provided by the root
    // subprojects {} block; junit-platform-launcher is on the test runtime.
}
