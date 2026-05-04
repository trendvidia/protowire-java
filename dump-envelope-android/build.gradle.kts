plugins {
    `java-library`
    application
}

// Lite-runtime mirror of :dump-envelope. Builds the same canonical envelope
// value and prints its protobuf-encoded bytes as hex, but uses
// protobuf-javalite end-to-end. The hex output must match :dump-envelope's
// exactly — divergence is caught cross-port by
// scripts/cross_envelope_check.sh's WITH_JAVA_LITE=1 branch in the canonical
// repo.

dependencies {
    implementation(project(":envelope-android"))
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelopeAndroid")
    applicationName = "dump-envelope-android"
}
