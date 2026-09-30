package org.ohmyloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.ohmyloader.gradle.native.NativeTarget
import org.ohmyloader.gradle.native.OmlNativeBuildSpec
import java.io.File
import javax.inject.Inject

/**
 * Rebuilds the `oml-native` library with zig when its sources changed. The plugin-side twin of the
 * loader build's `oml.build.BuildOmlNativeTask` — the two repositories cannot share a *task* class,
 * so each carries its own copy of `OmlNativeBuildSpec` (this one at
 * `src/main/kotlin/org/ohmyloader/gradle/native/`, the loader's at `build-logic/oml-native-build/`;
 * kept in sync by hand) for the platform matrix and the zig-out -> layout naming.
 * Incrementality: [sourceFiles] vs [buildOutputDir] fingerprints
 * (zig's own cache compiles only what changed underneath); zig's cross compilation is free — every target
 * is a `-Dtarget=` flag — so the *whole* platform matrix is built, and each target deploys straight after
 * its own build into the `build/<plat>/<arch>/release/` layout that [OmlNativeLayout.candidatePaths]
 * resolves, because every target installs into the same `zig-out/` and a later target would overwrite an
 * earlier one of the same kind.
 */
@CacheableTask
internal abstract class OmlBuildNative : DefaultTask() {

    @get:Inject
    protected abstract val exec: ExecOperations

    /** The `oml-native` zig project root: contains `build.zig`, `build.zig.zon` and `src/`. */
    @get:Internal
    abstract val nativeProjectDir: DirectoryProperty

    /** Everything that goes into the library: the zig sources and the build recipe itself. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** The output tree; its content snapshot is what makes the task skippable. */
    @get:OutputDirectory
    abstract val buildOutputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val dir = nativeProjectDir.get().asFile
        val matrix = OmlNativeBuildSpec.targets
        for (target in matrix) {
            val result = exec.exec { spec ->
                spec.workingDir = dir
                spec.executable = "zig"
                // zig's standardOptimizeOption defaults to Debug; ReleaseFast must be explicit.
                spec.args("build", "-Dtarget=${target.triple}", "-Doptimize=ReleaseFast")
                spec.isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException(
                    "zig failed to build oml-native for ${target.triple} (exit ${result.exitValue}); " +
                        "see the output above",
                )
            }
            deploy(dir, target)
        }
        logger.lifecycle(
            "oml-native rebuilt via zig: {} targets ({}), deployed under {}/build",
            matrix.size,
            matrix.joinToString(" ") { it.triple },
            dir
        )
    }

    /** Copies one target's built library from `zig-out` into its platform's layout slot. */
    private fun deploy(dir: File, target: NativeTarget) {
        val installedName = OmlNativeBuildSpec.installedName(target)
        val built = OmlNativeBuildSpec.zigOutDirs(target).asSequence()
            .map { File(File(dir, "zig-out"), it) }
            .map { File(it, installedName) }
            .firstOrNull { it.isFile }
            ?: throw GradleException(
                "zig build succeeded for ${target.triple} but produced no $installedName under ${File(dir, "zig-out")}",
            )
        val destination = OmlNativeBuildSpec.layoutDir(dir, target).apply { mkdirs() }
        built.copyTo(File(destination, "oml-native.${target.ext}"), overwrite = true)
        logger.lifecycle("oml-native {}: {} -> {}", target.triple, built.name, destination)
    }
}
