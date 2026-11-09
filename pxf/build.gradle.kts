// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.1"
}

dependencies {
    api(project(":pxf-runtime"))
    api("com.google.protobuf:protobuf-java:4.36.2")
    api(project(":proto-annotations"))

    testImplementation(project(":pb"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
}

// `src/test/proto` is the protobuf plugin's default — registering it
// explicitly via sourceSets adds it twice, which Gradle 9 rejects with
// a duplicate-entry error in `processTestProtoResources`.

// The spec corpus, one copy for both tiers: ../testdata/<dir> is protowire's
// testdata/<dir> byte for byte (the conformance job diffs it against spec
// main). Its schemas compile here, and its fixtures are test resources at
// /<dir>/<file>. Two schemas stay out: settings_annotated.proto needs a v1.2
// compiler, and protoc 3.25.5 reads nulls_editions.proto only behind
// --experimental_editions, so the test loads a checked-in descriptor set.
sourceSets {
    test {
        proto {
            srcDir(rootDir.resolve("testdata"))
            exclude("annotations/settings_annotated.proto", "nulls/nulls_editions.proto", "test.proto")
        }
        resources {
            srcDir(rootDir.resolve("testdata"))
            exclude("**/*.proto")
        }
    }
}

tasks.test {
    systemProperty("protowire.testdata", rootDir.resolve("testdata").absolutePath)
    systemProperty("protowire.goVerdicts", rootDir.resolve("scripts/go-verdicts/corpus.tsv").absolutePath)
    inputs.file(rootDir.resolve("scripts/go-verdicts/corpus.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
}
