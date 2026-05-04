plugins {
    `java-library`
    application
}

// PXF-driven mirror of :dump-envelope-android. Builds the canonical envelope
// not via the typed `Envelope.newBuilder()` chain, but by parsing a PXF text
// representation through Parser → LiteWireWriter → MessageLite.parseFrom →
// .toByteArray(). The resulting hex must match :dump-envelope-android's
// (which goes the direct toByteArray() route) — divergence catches an
// encoder-side wire bug in :pxf-android that the protobuf-javalite-direct
// path can't.
//
// The PxfMeta classes for the envelope schema are hand-authored (in this
// module's src/main/java/org/protowire/envelope/v1/) because the codegen
// plugin from the canonical repo isn't yet wired into protowire-java's
// build. When that wiring lands they're a candidate for replacement by
// auto-generated equivalents.

dependencies {
    implementation(project(":envelope-android"))
    implementation(project(":pxf-android"))
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelopePxfAndroid")
    applicationName = "dump-envelope-pxf-android"
}
