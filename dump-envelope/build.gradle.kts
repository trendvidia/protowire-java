plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":envelope"))
}

application {
    mainClass.set("com.trendvidia.protowire.dump.DumpEnvelope")
    applicationName = "dump-envelope"
}
