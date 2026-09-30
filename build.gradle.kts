// A standalone project: the loader itself is consumed by Maven coordinate only (publishToMavenLocal
// first), never by source — a POM or publication defect fails here, not on a consumer's release day.
// The Kotlin plugin is applied directly rather than via `kotlin-dsl`: kotlin-dsl pins the compiler
// embedded in Gradle, which cannot target Java 27 bytecode.

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlinJvm)
}

group = providers.gradleProperty("oml_group").getOrElse("org.ohmyloader")
version = providers.gradleProperty("oml_version").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

dependencies {
    // The Gradle API is supplied by whichever Gradle runs the plugin.
    compileOnly(gradleApi())
    // The typed downloader API this plugin calls in-process.
    implementation("org.ohmyloader:oml-devtools:$version")
    // gradleApi() brings ProjectBuilder: tests apply the plugin to a throwaway project without a
    // real build, daemon, or network.
    testImplementation(gradleApi())
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
}

gradlePlugin {
    plugins {
        create("oml") {
            id = "org.ohmyloader.gradle"
            implementationClass = "org.ohmyloader.gradle.OmlPlugin"
            displayName = "OhMyLoader"
            description = "Build support for OhMyLoader mods: game dependencies, library and native " +
                "fetching, and debug launch tasks for the client and the dedicated server."
        }
    }
}

// No manifest decoration: the plugin is a guest inside someone else's build and must not attach
// anything to jars it does not own.

publishing {
    // GitHub Packages is the interim Maven server until a dedicated one exists. Attached only
    // inside GitHub Actions (tag builds publish there); locally, publishToMavenLocal is the
    // whole story and nothing else is configured.
    if (providers.environmentVariable("GITHUB_ACTIONS").isPresent) {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/OhMyLoader/OhMyLoaderGradle")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("oml-gradle")
                description.set("OhMyLoader build plugin")
                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    }
                }
            }
        }
    }
}
