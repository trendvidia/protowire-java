// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
    application
}

// The lite tier's `check-decode`, for protowire's conformance gates. A codegen
// port can't bind a message from a descriptor set at runtime, so this ignores
// --proto and dispatches --schema to companions generated in
// :pxf-android-codegen-it.
dependencies {
    implementation(project(":pxf-android-codegen-it"))
    implementation(project(":pxf-android"))

    configurations.all {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

application {
    mainClass.set("org.protowire.check.android.CheckDecodeAndroid")
    applicationName = "check-decode-android"
}
