// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

// Lite-runtime mirror of :envelope. Generates the same classes
// (Envelope / AppError / FieldError under org.protowire.envelope.v1) from
// the canonical envelope.proto source-of-truth in :envelope, but with
// protoc emitting against protobuf-javalite — strictly smaller code, no
// descriptor reflection, dex-method-count-friendly on Android.
//
// In addition to the lite-Java codegen, this module invokes
// `protoc-gen-pxf-java-meta` (the Go plugin in the canonical
// `protowire/cmd/protoc-gen-pxf-java-meta/` directory). That emits the
// per-message {@code <Message>PxfMeta} companion classes the lite-runtime
// encoder needs at runtime — same package, same source-set, generated
// alongside the lite-Java classes themselves. Consumers (e.g.
// :dump-envelope-pxf-android) just `implementation(project(":envelope-android"))`
// and pick up both the message classes and their PxfMeta companions.
//
// Build prerequisite: a Go toolchain on PATH (see the plugin-build block below
// for how the spec repo is located or fetched).
//
// The lite and full classes share a Java package; consumers get one or the
// other depending on which module they depend on. They never appear on the
// same classpath because :dump-envelope-android and
// :dump-envelope-pxf-android are their own application distributions.

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.1")
    api(project(":pxf-runtime"))  // generated PxfMeta classes implement org.protowire.pxf.PxfMeta

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
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
            // Late-bound: pxfJavaMetaPlugin is a build output, so providers().get()
            // resolves it after buildPxfJavaMetaPlugin runs (see dependsOn below).
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
                id("pxf-java-meta") {}
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("../envelope/src/main/proto")
        }
    }
}
