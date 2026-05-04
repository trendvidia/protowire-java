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
// The lite and full classes share a Java package; consumers get one or the
// other depending on which module they depend on. They never appear on the
// same classpath because :dump-envelope-android (the only consumer) is its
// own application distribution.

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.5")

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
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
