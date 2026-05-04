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
    ":pxf-android",
    ":pxf-android-codegen-it",
    ":sbe",
    ":envelope",
    ":envelope-android",
    ":dump-envelope",
    ":dump-envelope-android",
    ":dump-envelope-pxf-android",
    ":bench-pxf",
    ":bench-sbe",
)
