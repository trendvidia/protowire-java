pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "protowire4java"

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
