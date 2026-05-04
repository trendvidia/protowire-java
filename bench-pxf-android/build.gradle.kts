import com.google.protobuf.gradle.id

plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.9.4"
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
// Same setup as :envelope-android / :pxf-android-codegen-it.

val canonicalPluginSrc = rootDir.resolve("../protowire/cmd/protoc-gen-pxf-java-meta").canonicalFile
val canonicalRepoDir   = rootDir.resolve("../protowire").canonicalFile
val pxfJavaMetaPlugin  = layout.buildDirectory.file("tools/protoc-gen-pxf-java-meta")

val buildPxfJavaMetaPlugin by tasks.registering(Exec::class) {
    description = "Builds the Go protoc-gen-pxf-java-meta binary from the canonical sibling repo."
    group = "build"
    inputs.dir(canonicalPluginSrc)
    outputs.file(pxfJavaMetaPlugin)
    workingDir = canonicalRepoDir
    commandLine = listOf(
        "go", "build",
        "-o", pxfJavaMetaPlugin.get().asFile.absolutePath,
        "./cmd/protoc-gen-pxf-java-meta"
    )
    doFirst {
        if (!canonicalPluginSrc.exists()) {
            throw GradleException(
                "expected the canonical protowire repo at ${canonicalRepoDir} (sibling to protowire-java); " +
                "needed to build protoc-gen-pxf-java-meta. Clone it from " +
                "https://github.com/trendvidia/protowire and place it next to this repo."
            )
        }
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

sourceSets {
    main {
        proto {
            srcDir("src/main/proto")
        }
    }
}
