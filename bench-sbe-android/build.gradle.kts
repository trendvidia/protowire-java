import com.google.protobuf.gradle.id

plugins {
    `java-library`
    application
    id("com.google.protobuf") version "0.9.4"
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
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation(project(":sbe-runtime"))
    // protoc-gen-pxf-java-meta emits PxfMeta + PxfCodec for every message
    // alongside the SBE companions, so the bench's compile classpath
    // needs the PXF runtime classes too.
    implementation(project(":pxf-android"))

    // sbe/annotations.proto is provided by :proto-annotations as an
    // include-only proto; descriptor.proto comes from the protobuf-java
    // jar at compile-only scope (it's stripped from javalite, but
    // sbe/annotations.proto imports it). Both stay off the runtime
    // classpath.
    compileOnly(project(":proto-annotations"))
    compileOnly("com.google.protobuf:protobuf-java:3.25.5")
}

application {
    mainClass.set("org.protowire.bench.BenchSbeAndroid")
    applicationName = "bench-sbe-android"
}

// --- protoc-gen-pxf-java-meta plugin build ---------------------------------

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
