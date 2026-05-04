import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
    api(project(":proto-annotations"))
    api(project(":sbe-runtime"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

sourceSets {
    test {
        proto {
            srcDir("src/test/proto")
        }
    }
}
