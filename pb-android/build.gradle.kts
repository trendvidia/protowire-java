plugins {
    `java-library`
}

// PB (compact protobuf-binary) codec on protobuf-javalite. The pb codec
// is just protobuf-javalite's own marshal/unmarshal — no protowire-
// specific encode/decode logic. This module exists to:
//
//   1. Publish under `org.protowire:pb-android` for symmetry with
//      `:pb`'s `org.protowire:pb` artifact (the JVM full-tier).
//   2. Pin the protobuf-javalite version downstream consumers get,
//      matching what `:pxf-android` / `:sbe-android` / `:envelope-android`
//      already require.
//
// No own code. Re-exports protobuf-javalite via `api`.

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}
