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
    ":pxf",
    ":sbe",
    ":envelope",
    ":dump-envelope",
    ":bench-pxf",
    ":bench-sbe",
)
