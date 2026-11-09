// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.1"
}

dependencies {
    api(project(":sbe-runtime"))
    api("com.google.protobuf:protobuf-java:3.25.5")
    api(project(":proto-annotations"))
    // The Validator seam the decoders share (#205).
    api(project(":check"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// `src/test/proto` is the protobuf plugin's default — registering it
// explicitly via sourceSets adds it twice, which Gradle 9 rejects with
// a duplicate-entry error in `processTestProtoResources`.
