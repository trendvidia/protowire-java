// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

// End-to-end smoke for the lite-tier SBE codegen path. Compiles
// order.proto with protoc-gen-java (lite mode) + protoc-gen-pxf-java-meta
// (also lite, which triggers the SbeMeta + SbeCodec emit added in
// canonical#33), and runs JUnit round-trips through the emitted
// NewOrderSingleSbeCodec.{marshal, unmarshal} pair. Validates the chain
// the descriptor-driven :sbe Codec covers, but through codegen-emitted
// typed dispatch instead of FieldDescriptor reflection.
//
// Build prerequisites match :envelope-android: a Go toolchain on PATH
// and the canonical protowire repo at ../../protowire/.

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.1")
    api(project(":sbe-runtime"))
    // The plugin emits PxfMeta + PxfCodec for every message in addition to
    // the SBE companions, so the smoke test needs :pxf-android (and its
    // :pxf-runtime transitive) to compile the emitted output.
    api(project(":pxf-android"))

    // sbe/annotations.proto is consumed via :proto-annotations as an
    // include-only proto source (extractIncludeProto pulls it without
    // re-generating Java for it — the existing :proto-annotations module
    // owns those classes).
    compileOnly(project(":proto-annotations"))

    // protobuf-javalite strips the bundled google/protobuf/descriptor.proto
    // that sbe/annotations.proto imports. Adding the full protobuf-java jar
    // at compile-only puts descriptor.proto on extractIncludeProto's path.
    // No transitive runtime escape: protobuf-java never enters the runtime
    // configurations.
    compileOnly("com.google.protobuf:protobuf-java:4.36.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// --- protoc-gen-pxf-java-meta plugin build ---------------------------------

// --- protoc-gen-pxf-java-meta (Go) plugin build -----------------------------
//
// The plugin lives in the spec repo (protowire/cmd/protoc-gen-pxf-java-meta).
// With a checkout of that repo beside this one (../protowire, overridable
// with -PpxfJavaMeta.repo=<dir>) it is built from source, so a plugin change
// can be tried before it is pushed. Without one -- CI, a fresh clone -- the
// spec repo is shallow-fetched at the ref pinned in gradle.properties
// (pxfJavaMeta.ref) and the plugin built from that. Either way the binary lands in build/tools and is reused
// for every protoc invocation. Both paths need a Go toolchain on PATH.

val pxfJavaMetaRepo   = rootDir.resolve(providers.gradleProperty("pxfJavaMeta.repo").getOrElse("../protowire")).canonicalFile
val pxfJavaMetaRef    = providers.gradleProperty("pxfJavaMeta.ref").get()
val pxfJavaMetaPlugin = layout.buildDirectory.file("tools/protoc-gen-pxf-java-meta")
val pxfJavaMetaFromSource = pxfJavaMetaRepo.resolve("cmd/protoc-gen-pxf-java-meta").isDirectory

val buildPxfJavaMetaPlugin by tasks.registering(Exec::class) {
    description = "Builds protoc-gen-pxf-java-meta from ../protowire, or go-installs it at pxfJavaMeta.ref."
    group = "build"
    val out = pxfJavaMetaPlugin.get().asFile
    outputs.file(pxfJavaMetaPlugin)
    if (pxfJavaMetaFromSource) {
        inputs.dir(pxfJavaMetaRepo.resolve("cmd/protoc-gen-pxf-java-meta"))
        workingDir = pxfJavaMetaRepo
        commandLine = listOf("go", "build", "-o", out.absolutePath, "./cmd/protoc-gen-pxf-java-meta")
    } else {
        // Not `go install pkg@ref`: the spec repo's go.mod carries a replace
        // directive, which Go refuses to honour outside the main module. A
        // shallow fetch of the pinned commit, built in place, honours it.
        inputs.property("pxfJavaMeta.ref", pxfJavaMetaRef)
        val src = layout.buildDirectory.dir("tools/protowire-src").get().asFile
        commandLine = listOf("sh", "-c", """
            set -e
            rm -rf "$1" && mkdir -p "$1" && cd "$1"
            git init -q
            git remote add origin https://github.com/trendvidia/protowire.git
            git fetch -q --depth 1 origin "$2"
            git checkout -q FETCH_HEAD
            go build -o "$3" ./cmd/protoc-gen-pxf-java-meta
        """.trimIndent(), "sh", src.absolutePath, pxfJavaMetaRef, out.absolutePath)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.1"
    }
    plugins {
        id("pxf-java-meta") {
            path = pxfJavaMetaPlugin.get().asFile.absolutePath
        }
    }
    generateProtoTasks {
        all().configureEach {
            dependsOn(buildPxfJavaMetaPlugin)
            builtins {
                named("java") {
                    option("lite")
                }
            }
            plugins {
                id("pxf-java-meta") {
                    option("lite")
                }
            }
        }
    }
}

