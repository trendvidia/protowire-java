plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":envelope"))
}

application {
    mainClass.set("org.protowire.dump.DumpEnvelope")
    applicationName = "dump-envelope"
}
