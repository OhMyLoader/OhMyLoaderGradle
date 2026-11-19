package org.ohmyloader.gradle

/**
 * The `<os>-<arch>` classifier spelling of the published `org.ohmyloader:oml-native` artifacts,
 * mapped onto the running JVM's platform.
 *
 * The classifier tokens are `windows` / `linux` / `osx` (the repository says `osx`, not `mac`), with
 * an `-arm64` suffix where the JVM is 64-bit ARM. The darwin check MUST precede the win check: a
 * darwin `os.name` containing "win" is the trap PlatformRules already documented for the downloader
 * (macOS selecting the Windows natives).
 */
internal object OmlNativeLayout {

    /** Platform token candidates for [osName], in preference order. */
    fun buildPlatforms(osName: String = System.getProperty("os.name", "")): List<String> {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> listOf("macosx")
            os.contains("win") -> listOf("windows")
            os.contains("nux") || os.contains("nix") -> listOf("linux")
            else -> emptyList()
        }
    }

    /** Architecture token candidates for [osArch], in preference order. */
    fun buildArchs(osArch: String = System.getProperty("os.arch", "")): List<String> {
        val arch = osArch.lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> listOf("arm64", "aarch64")
            else -> listOf("x64", "x86_64", "amd64")
        }
    }

    /**
     * The `<os>` token the published `natives-<os>` classifiers are spelled with (the repository says
     * `osx`, not `mac`).
     *
     * Fails loudly on an unrecognized `os.name`: defaulting to some platform would resolve a library
     * this JVM cannot load, and an unresolved classifier only surfaces later as a link error.
     */
    fun nativeClassifierOs(osName: String = System.getProperty("os.name", "")): String =
        buildPlatforms(osName).firstOrNull()?.let { if (it == "macosx") "osx" else it }
            ?: throw org.gradle.api.GradleException(
                "unsupported os.name '$osName': cannot choose an oml-native classifier " +
                    "(supported: Windows, Linux, macOS).",
            )

    /** The classifier architecture suffix: `-arm64` where the JVM is 64-bit ARM, empty for x86_64. */
    fun nativeClassifierArchSuffix(osArch: String = System.getProperty("os.arch", "")): String =
        if (buildArchs(osArch).first() in setOf("arm64", "aarch64")) "-arm64" else ""
}
