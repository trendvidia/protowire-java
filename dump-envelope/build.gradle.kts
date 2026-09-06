// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":envelope"))
    implementation(project(":pxf"))
    implementation(project(":sbe"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelope")
    applicationName = "dump-envelope"
}
