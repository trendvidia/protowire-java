// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":envelope"))
    implementation(project(":pb"))
    implementation(project(":pxf"))
    implementation(project(":sbe"))
    implementation("com.google.protobuf:protobuf-java:4.36.2")
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelope")
    applicationName = "dump-envelope"
}
