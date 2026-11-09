// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
//
// The validation seam the decoders share (#205), as protowire-go's check
// package: the Validator interface, Violation and Report, the exception a
// decode fails with when a validator reports violations, and Check.validate.
// No dependencies, so pxf, pb and sbe can each depend on it, and validation
// engines plug in without the codecs depending on any of them.
plugins {
    `java-library`
}

dependencies {
}
