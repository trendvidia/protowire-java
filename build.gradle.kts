plugins {
    `java-library`
    `maven-publish`
}

allprojects {
    group = "com.trendvidia.protowire"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    tasks.withType<AbstractCopyTask>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
