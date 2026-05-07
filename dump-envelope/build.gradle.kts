// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":envelope"))
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelope")
    applicationName = "dump-envelope"
}
