// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

plugins {
    `java-library`
    application
}

// Lite-tier mirror of :bench-sbe. Times SBE marshal + unmarshal of
// `bench.v1.Order` against the canonical sbe-bench.proto fixture, but
// routes the work through OrderSbeCodec.{marshal, unmarshal} (which
// compose protowire-java#21's :sbe-runtime SbeWireCodec with codegen-
// emitted typed reader/writer dispatch) instead of the full-runtime
// descriptor-driven :sbe Codec. Output JSON shape matches :bench-sbe's
// so scripts/cross_sbe_bench.sh aggregates them under the `java-lite`
// port row alongside the JVM `java` numbers.
//
// Build prerequisites match :envelope-android's: a Go toolchain on PATH +
// the canonical protowire repo at ../../protowire/.

dependencies {
    implementation(project(":lite-fixtures"))
    implementation("com.google.protobuf:protobuf-javalite:4.36.1")
    // protoc-gen-pxf-java-meta emits PxfMeta + PxfCodec for every message
    // alongside the SBE companions, so the bench's compile classpath
    // needs the PXF runtime classes too.

    // sbe/annotations.proto is provided by :proto-annotations as an
    // include-only proto; descriptor.proto comes from the protobuf-java
    // jar at compile-only scope (it's stripped from javalite, but
    // sbe/annotations.proto imports it). Both stay off the runtime
    // classpath.
    compileOnly(project(":proto-annotations"))
    compileOnly("com.google.protobuf:protobuf-java:4.36.1")
}

application {
    mainClass.set("org.protowire.bench.BenchSbeAndroid")
    applicationName = "bench-sbe-android"
}

// --- protoc-gen-pxf-java-meta plugin build ---------------------------------


