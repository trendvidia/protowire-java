import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
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
// Build prerequisites: a Go toolchain on PATH, and the canonical
// protowire repo checked out as a sibling at `../../protowire/`.
//
// The lite and full classes share a Java package; consumers get one or the
// other depending on which module they depend on. They never appear on the
// same classpath because :dump-envelope-android and
// :dump-envelope-pxf-android are their own application distributions.

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")
    api(project(":pxf-runtime"))  // generated PxfMeta classes implement org.protowire.pxf.PxfMeta

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

// --- protoc-gen-pxf-java-meta (Go) plugin build -----------------------------
//
// The plugin source lives in the sibling canonical repo at
// `../../protowire/cmd/protoc-gen-pxf-java-meta`. Build it once into
// `build/tools/protoc-gen-pxf-java-meta` and reuse for every protoc invocation.

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
