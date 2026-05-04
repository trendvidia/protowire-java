plugins {
    `java-library`
}

// Descriptor-free SBE primitives: data-only templates (FieldTemplate /
// GroupTemplate / MessageTemplate), the wire-level marshal / unmarshal
// codec, and the abstract SbeFieldReader / SbeFieldWriter interfaces the
// codec drives. No dependency on protobuf-java or the proto-annotations
// module — those live one layer up in :sbe, which adds the descriptor-
// driven adapter (MessageReader / MessageWriter) and template builder on
// top of these pieces.
//
// Mirrors the :pxf-runtime split: this module exists so the upcoming
// :sbe-android (lite-runtime) variant can share the same wire-codec path
// without pulling in full-runtime protobuf or descriptor reflection.
//
// compileOnly on protobuf-javalite gives us the ByteString class without
// committing to a runtime artifact — :sbe and (future) :sbe-android each
// bring their own protobuf flavor.

dependencies {
    compileOnly("com.google.protobuf:protobuf-javalite:3.25.5")
}
