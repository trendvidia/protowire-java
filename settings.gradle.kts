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
    ":sbe",
    ":envelope",
    ":dump-envelope",
    ":bench-pxf",
    ":bench-sbe",
)
