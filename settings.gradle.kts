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
    ":pb-android",
    ":pxf-runtime",
    ":pxf",
    ":pxf-android",
    ":pxf-android-codegen-it",
    ":sbe-runtime",
    ":sbe",
    ":sbe-android",
    ":sbe-android-codegen-it",
    ":envelope",
    ":envelope-android",
    ":dump-envelope",
    ":dump-envelope-android",
    ":dump-envelope-pxf-android",
    ":bench-pxf",
    ":bench-pxf-android",
    ":bench-sbe",
    ":bench-sbe-android",
    ":check-decode",
)
