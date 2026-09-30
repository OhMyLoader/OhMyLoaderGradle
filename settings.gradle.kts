// An independent project: everything cross-project is a Maven coordinate. Development loop:
// `gradlew publishToMavenLocal` in OhMyLoader/ first, then resolve from mavenLocal.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }

    // The catalog is this repo's own copy: each build clones and builds standalone, so nothing
    // points across the repo boundary. Keep the Kotlin version in sync with the loader's by hand —
    // an older compiler here fails on the Java 27 class file version.
}

rootProject.name = "oml-gradle"
