import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
    api("io.grpc:grpc-stub:1.66.0")
    api("io.grpc:grpc-protobuf:1.66.0")
    api("io.grpc:grpc-netty-shaded:1.66.0")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.66.0"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc") { }
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
