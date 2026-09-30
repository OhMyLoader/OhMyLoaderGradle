package org.ohmyloader.gradle.native

import java.io.File

/**
 * The oml-native zig build declaration for the plugin's side: the cross-compiled platform matrix and
 * the naming/spelling rules that turn one zig target into its slot under the build layout.
 *
 * This is the plugin project's own copy; a second one lives at
 * `OhMyLoader/build-logic/oml-native-build/OmlNativeBuildSpec.kt` for the loader build — the two builds
 * cannot see each other's classes, so the matrix exists twice and must be kept in sync by hand (a drift
 * shows up as a natives layout mismatch in `OmlNativeLayout`, not as a silent misbuild). The layout
 * spellings are `x64` on Windows, `x86_64` elsewhere, and the macOS platform directory is `macosx`, not
 * `mac` — `OmlNativeLayout` and the installer resolve exactly those names out of the build tree.
 */
internal data class NativeTarget(val plat: String, val arch: String, val triple: String, val ext: String)

internal object OmlNativeBuildSpec {

    /**
     * The whole matrix. Zig's cross compilation is free — every target is a `-Dtarget=` flag, no
     * foreign toolchain — so the full windows/linux/macosx times x64/arm64 grid is built, not just
     * the running machine.
     */
    val targets: List<NativeTarget> = listOf(
        NativeTarget("windows", "x64", "x86_64-windows-gnu", "dll"),
        NativeTarget("windows", "arm64", "aarch64-windows-gnu", "dll"),
        NativeTarget("linux", "x86_64", "x86_64-linux-gnu", "so"),
        NativeTarget("linux", "arm64", "aarch64-linux-gnu", "so"),
        NativeTarget("macosx", "x86_64", "x86_64-macos", "dylib"),
        NativeTarget("macosx", "arm64", "aarch64-macos", "dylib"),
    )

    /** The name zig installs the artifact under: it prefixes on Unix only, Windows keeps it bare. */
    fun installedName(target: NativeTarget): String =
        if (target.ext == "dll") "oml-native.dll" else "liboml-native.${target.ext}"

    /** Where that artifact lands inside `zig-out/`, most likely first (Windows splits into `bin/`). */
    fun zigOutDirs(target: NativeTarget): List<String> =
        if (target.ext == "dll") listOf("bin", "lib") else listOf("lib", "bin")

    /** The consumer-facing slot: `build/<plat>/<arch>/release/`, the layout the resolver reads. */
    fun layoutDir(projectDir: File, target: NativeTarget): File =
        File(projectDir, "build/${target.plat}/${target.arch}/release")
}