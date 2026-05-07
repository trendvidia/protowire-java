// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

allprojects {
    group = "org.protowire"
    version = "0.70.0"

    repositories {
        mavenCentral()
    }
}

// Modules that ship to Maven Central. Bench harnesses + dump-envelope are
// internal test runners (consumed by the spec repo's cross_*.sh scripts)
// and intentionally excluded.
val publishableModules = setOf("pb", "pxf", "sbe", "envelope", "proto-annotations")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

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
        finalizedBy(tasks.withType<JacocoReport>())
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)   // for CodeCov / coverage tooling
            html.required.set(true)
        }
    }

    tasks.withType<AbstractCopyTask>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // --- Maven Central publishing (only for library modules) -------------
    // Bench / dump harnesses are not published.
    if (project.name in publishableModules) {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Central Portal is the new Sonatype home; OSSRH is being retired.
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
            signAllPublications()

            coordinates("org.protowire", "protowire-${project.name}", project.version.toString())

            configure(
                JavaLibrary(
                    javadocJar = JavadocJar.Javadoc(),
                    sourcesJar = true,
                ),
            )

            pom {
                name.set("protowire-${project.name}")
                description.set(
                    project.findProperty("pomDescription") as String?
                        ?: "Java port of the protowire ${project.name} module — wire-format toolkit.",
                )
                url.set("https://protowire.org")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("trendvidia")
                        name.set("TrendVidia, LLC.")
                        url.set("https://trendvidia.com")
                    }
                }

                scm {
                    url.set("https://github.com/trendvidia/protowire-java")
                    connection.set("scm:git:https://github.com/trendvidia/protowire-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/trendvidia/protowire-java.git")
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/trendvidia/protowire-java/issues")
                }
            }
        }
    }
}
