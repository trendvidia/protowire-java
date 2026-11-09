// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":sbe"))
    implementation("com.google.protobuf:protobuf-java:4.36.2")
}

application {
    mainClass.set("org.protowire.bench.BenchSbe")
    applicationName = "bench-sbe"
}
