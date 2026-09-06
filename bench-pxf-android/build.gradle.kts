// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.10.0"
}

// Lite-tier mirror of :bench-pxf. Times PXF unmarshal + marshal of
// `bench.v1.Config` against the canonical testdata fixtures, but routes
// the work through ConfigPxfCodec.unmarshal / marshal (which compose
// Parser → LiteWireWriter → MessageLite.parseFrom and the reverse) instead
// of the full-runtime DynamicMessage path. Output JSON shape matches
// :bench-pxf's so scripts/cross_pxf_bench.sh aggregates them under the
// `java-lite` port row alongside the JVM `java` numbers.
//
// Build prerequisites match :envelope-android's: a Go toolchain on PATH +
// the canonical protowire repo at ../../protowire/ (used to build the
// protoc-gen-pxf-java-meta plugin).

dependencies {
    implementation(project(":pxf-android"))
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

application {
    mainClass.set("org.protowire.bench.BenchPxfAndroid")
    applicationName = "bench-pxf-android"
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
        artifact = "com.google.protobuf:protoc:3.25.5"
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
                // `lite` enables PxfCodec emission with both unmarshal()
                // and marshal() entry points — the bench harness uses both.
                id("pxf-java-meta") {
                    option("lite")
                }
            }
        }
    }
}
