plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":pxf"))
    implementation(project(":sbe"))
    implementation(project(":registry-client"))
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}

application {
    mainClass.set("com.trendvidia.protowire.cli.Main")
}
