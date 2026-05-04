plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":pxf"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")
}

application {
    mainClass.set("org.protowire.bench.BenchPxf")
    applicationName = "bench-pxf"
}
