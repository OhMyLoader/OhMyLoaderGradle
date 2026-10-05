package org.ohmyloader.gradle

/**
 * Where the oml-native build tasks leave the built library, mapped onto the running JVM's platform.
 *
 * The build tree is named `build/<plat>/<arch>/release/`, with the spellings `x64` on Windows, `x86_64`
 * on Linux and `macosx` (not `mac`) on macOS. The runtime contract everywhere else in this repository
 * speaks `<os>-<arch>` tokens (`windows-x86_64`, `osx-arm64`); this resolver bridges the two so the
 * Gradle deploy can find the library without hardcoding a single platform.
 * The darwin check MUST precede the win check: a darwin `os.name` containing "win" is the trap
 * PlatformRules already documented for the downloader (macOS selecting the Windows natives).
 */
internal object OmlNativeLayout {

    /** Build-tree platform directory candidates for [osName], in preference order. */
    fun buildPlatforms(osName: String = System.getProperty("os.name", "")): List<String> {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> listOf("macosx")
            os.contains("win") -> listOf("windows")
            os.contains("nux") || os.contains("nix") -> listOf("linux")
            else -> emptyList()
        }
    }

    /** Build-tree architecture directory candidates for [osArch], in preference order. */
    fun buildArchs(osArch: String = System.getProperty("os.arch", "")): List<String> {
        val arch = osArch.lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> listOf("arm64", "aarch64")
            else -> listOf("x64", "x86_64", "amd64")
        }
    }

    /**
     * The `<os>` token the published `natives-<os>` classifiers are spelled with (the repository says
     * `osx`, the build tree says `macosx`).
     *
     * Fails loudly on an unrecognized `os.name`: defaulting to some platform would resolve a library
     * this JVM cannot load, and an unresolved classifier only surfaces later as a link error.
     */
    fun nativeClassifierOs(osName: String = System.getProperty("os.name", "")): String =
        buildPlatforms(osName).firstOrNull()?.let { if (it == "macosx") "osx" else it }
            ?: throw org.gradle.api.GradleException(
                "unsupported os.name '$osName': cannot choose an oml-native classifier " +
                    "(supported: Windows, Linux, macOS). Point oml.nativeProjectDir at a local zig build, " +
                    "or run on a supported platform.",
            )

    /** The classifier architecture suffix: `-arm64` where the JVM is 64-bit ARM, empty for x86_64. */
    fun nativeClassifierArchSuffix(osArch: String = System.getProperty("os.arch", "")): String =
        if (buildArchs(osArch).first() in setOf("arm64", "aarch64")) "-arm64" else ""

    /** The library file name the oml-native build produces on [osName] (an empty list platform yields `.so`). */
    fun libraryFileName(osName: String = System.getProperty("os.name", "")): String {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "oml-native.dylib"
            os.contains("win") -> "oml-native.dll"
            else -> "oml-native.so"
        }
    }

    /**
     * Absolute paths inside [buildDir] where the current platform's library may sit, most
     * specific first. Callers take the first existing one.
     */
    fun candidatePaths(buildDir: java.io.File): List<java.io.File> =
        buildPlatforms().flatMap { plat ->
            buildArchs().map { arch -> java.io.File(buildDir, "$plat/$arch/release") }
        }.map { dir -> java.io.File(dir, libraryFileName()) }
}
