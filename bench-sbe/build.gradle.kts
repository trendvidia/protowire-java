plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":sbe"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")
}

application {
    mainClass.set("org.protowire.bench.BenchSbe")
    applicationName = "bench-sbe"
}
