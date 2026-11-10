// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.1"
}

// The spec corpus schemas (../testdata, protowire's testdata byte for byte),
// generated lite with their protoc-gen-pxf-java-meta companions, for the lite
// corpus test and for the --pb/--sbe modes both lite dumpers expose to
// scripts/cross_envelope_check.sh:
//
//   testdata/annotations/settings.proto  (pxf.required) = 1314, (pxf.default) = 1315
//   testdata/sbe-bench.proto             (sbe.*) 1319-1323, bench.v1.Order (src/main/proto)
//
// A codegen port cannot bind a message from a descriptor set at runtime, so
// the dumpers name a type generated here and ignore the FDS argument; what
// the gate then proves is that the plugin lifted the annotations from the
// registered numbers and that LiteWireWriter / <Message>SbeCodec honour them.
// bench-sbe-android takes its Order from here too. Not published.
dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.2")
    api(project(":pxf-android"))
    api(project(":sbe-runtime"))
    // pxf.BigFloat and pxf.Secret, which the corpus schemas import, generated
    // lite with their companions.
    api(project(":pxf-android-codegen-it"))
    compileOnly(project(":proto-annotations"))
    // compile-only, like sbe-android-codegen-it: the well-known protos on the
    // proto include path come from this jar; it is not on the runtime classpath.
    compileOnly("com.google.protobuf:protobuf-java:4.36.2")
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

// protoc-gen-pxf-java-meta requires a java_package, and the spec's schemas
// carry none, so the build adds one (the proto package, as protoc's default
// would be) and java_multiple_files. Nothing else in a file changes.
// settings_annotated.proto needs a v1.2 compiler: src/main/proto spells it for
// stock protoc.
val corpusProto = layout.buildDirectory.dir("corpus-proto")
val prepareCorpusProto by tasks.registering(Sync::class) {
    from(rootDir.resolve("testdata")) {
        include("**/*.proto")
        exclude("annotations/settings_annotated.proto")
    }
    into(corpusProto)
    filter { line: String ->
        val m = Regex("""^package\s+([\w.]+)\s*;""").find(line)
        if (m == null) line
        else "$line\noption java_package = \"${m.groupValues[1]}\";\noption java_multiple_files = true;"
    }
}

sourceSets {
    main {
        proto {
            srcDir(prepareCorpusProto)
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Read through system properties, so declared as inputs: a changed
    // fixture or Go verdict reruns the tests rather than reusing a result.
    systemProperty("protowire.testdata", rootDir.resolve("testdata").absolutePath)
    systemProperty("protowire.goVerdicts", rootDir.resolve("scripts/go-verdicts/corpus.tsv").absolutePath)
    inputs.dir(rootDir.resolve("testdata")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("scripts/go-verdicts/corpus.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("protowire.bigFloatText", rootDir.resolve("scripts/go-bigfloat-text/go.tsv").absolutePath)
    inputs.file(rootDir.resolve("scripts/go-bigfloat-text/go.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
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

