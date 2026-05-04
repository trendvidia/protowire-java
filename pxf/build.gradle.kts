import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    api(project(":pxf-runtime"))
    api("com.google.protobuf:protobuf-java:3.25.5")
    api(project(":proto-annotations"))

    testImplementation(project(":pb"))
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
