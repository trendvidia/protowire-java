// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
plugins {
    `java-library`
}

dependencies {
    api("com.google.protobuf:protobuf-java:4.36.2")
    // BigFloats: the one pxf.BigFloat normalisation rule, shared with the
    // PXF codecs (#159). Dependency-free, and not part of this module's API.
    implementation(project(":pxf-runtime"))
    // The Validator seam the decoders share (#205): part of this module's API.
    api(project(":check"))
}
