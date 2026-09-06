// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "protowire-java"

include(
    ":proto-annotations",
    ":pb",
    ":pxf-runtime",
    ":pxf",
    ":sbe-runtime",
    ":sbe",
    ":envelope",
    ":dump-envelope",
    ":bench-pxf",
    ":bench-sbe",
    ":check-decode",
    // Lite tier (protobuf-javalite): see #60.
    ":pb-android",
    ":pxf-android",
    ":envelope-android",
    ":pxf-android-codegen-it",
    ":dump-envelope-android",
    ":dump-envelope-pxf-android",
    ":bench-pxf-android",
)
