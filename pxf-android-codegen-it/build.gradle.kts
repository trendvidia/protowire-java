// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

// End-to-end smoke covering the full lite codegen chain: schema → protoc
// (lite Java) + protoc-gen-pxf-java-meta plugin (PxfMeta + PxfCodec) →
// JUnit round-trip tests using the emitted <Message>PxfCodec.unmarshal /
// marshal entry points.
//
// Validates the chain end-to-end on a WKT-bearing schema — :envelope-android
// has no well-known-type fields, so it doesn't exercise WELL_KNOWN_KINDS or
// the WKT decoder fast paths through the codegen path. This module fills
// that gap with a single AllWkt fixture covering Timestamp, Duration, the
// scalar wrappers, and pxf.{BigInt, Decimal, BigFloat}.
//
// Build prerequisites match :envelope-android's: a Go toolchain on PATH, and
// the canonical protowire repo checked out as a sibling at `../../protowire/`.

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.1")
    api(project(":pxf-runtime"))
    api(project(":pxf-android"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

tasks.test {
    useJUnitPlatform()
}

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
                // The `lite` parameter triggers the plugin's <Message>PxfCodec
                // emission with both unmarshal() and marshal() entry points.
                id("pxf-java-meta") {
                    option("lite")
                }
            }
        }
    }
}

// pxf/bignum.proto is the canonical PXF arbitrary-precision-number schema
// living at proto-annotations/src/main/proto/pxf/bignum.proto in the JVM tier
// (compiled with protobuf-java there). For the lite tier we re-compile it
// here under the same java_package so protoc-gen-pxf-java-meta's emitted
// references to org.protowire.proto.pxf.{BigInt,Decimal,BigFloat} resolve
// correctly. Including the JVM-tier module's whole srcDir would drag in
// pxf/annotations.proto and sbe/annotations.proto, which need
// google/protobuf/descriptor.proto on the include path — needless coupling.