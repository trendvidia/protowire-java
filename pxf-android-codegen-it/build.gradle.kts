import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
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
    api("com.google.protobuf:protobuf-javalite:3.25.5")
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
// Same setup as :envelope-android — both modules build the plugin from source
// and feed it to protoc as a custom output.

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
sourceSets {
    main {
        proto {
            srcDir("src/main/proto")
        }
    }
}
