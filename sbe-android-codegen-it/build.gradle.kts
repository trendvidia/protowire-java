import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
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
    api("com.google.protobuf:protobuf-javalite:3.25.5")
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
    compileOnly("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
