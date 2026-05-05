plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":pxf"))
    implementation(project(":pb"))
    implementation("com.google.protobuf:protobuf-java:3.25.5")
}

application {
    mainClass.set("org.protowire.check.CheckDecode")
    applicationName = "check-decode"
}
