// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// `src/main/proto` is the protobuf plugin's default — registering it
// explicitly via sourceSets adds it twice, which Gradle 9 rejects with
// a duplicate-entry error in `processResources`.
