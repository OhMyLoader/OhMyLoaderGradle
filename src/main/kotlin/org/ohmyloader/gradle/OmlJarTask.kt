package org.ohmyloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.ohmyloader.core.mod.ModScanner
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Produces the distributable mod jar: the project's `jar` output plus OML metadata in the
 * manifest, named `<mod-id>-<version>.jar` (or `<project>-<version>.jar` when one jar carries
 * several mods). The metadata scan runs with the loader's own [ModScanner], so a jar this task
 * accepts is exactly a jar the loader will read — the same @Mod contract, not a second one that
 * could drift.
 */
@CacheableTask
abstract class OmlJarTask : DefaultTask() {

    /** The built mod jar (the Java plugin's `jar` task output). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    /** Where the distributable jar is written. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** The consumer project's name — the distribution's file name when one jar carries several mods. */
    @get:Input
    abstract val projectName: Property<String>

    /** The consumer project's version — paired with [projectName] in the multi-mod file name. */
    @get:Input
    abstract val projectVersion: Property<String>

    @TaskAction
    fun run() {
        val source = jarFile.get().asFile
        // ModScanner.scan reads every jar in a directory; the staging copy keeps the scan pointed
        // at exactly this jar even when the build directory holds other artifacts.
        val staging = kotlin.io.path.createTempDirectory("omljar-scan").toFile()
        try {
            val scanMods = File(staging, "mods").apply { mkdirs() }
            source.copyTo(File(scanMods, source.name), overwrite = true)

            val mods = ModScanner.scan(scanMods)
            if (mods.isEmpty()) {
                throw IllegalStateException(
                    "$source carries no @Mod-annotated class — a jar without an entry point is not a distributable OML mod"
                )
            }
            val duplicateIds = mods.groupBy { it.id }.filterValues { it.size > 1 }
            if (duplicateIds.isNotEmpty()) {
                throw IllegalStateException(
                    "two @Mod classes in $source declare the same id: ${duplicateIds.keys.joinToString(", ")}"
                )
            }

            val output = File(outputDirectory.get().asFile, distributionName(mods)).apply { parentFile.mkdirs() }
            writeDistributable(source, output, mods)
            println("[oml] distributable mod jar: ${output.path}")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun distributionName(mods: List<org.ohmyloader.core.mod.ModContainer>): String =
        if (mods.size == 1) "${mods[0].id}-${mods[0].version}.jar"
        else "${projectName.get()}-${projectVersion.get()}.jar"

    private fun writeDistributable(
        source: File,
        output: File,
        mods: List<org.ohmyloader.core.mod.ModContainer>,
    ) {
        val manifest = Manifest()
        val main = manifest.mainAttributes
        main[Attributes.Name.MANIFEST_VERSION] = "1.0"
        main[Attributes.Name("OML-Mod-Id")] = mods.joinToString(", ") { it.id }
        // One manifest section per mod: the version and the dependency specs stay attached to the
        // mod they belong to even when one jar carries several.
        for (mod in mods) {
            val section = Attributes()
            section[Attributes.Name("OML-Mod-Version")] = mod.version
            if (mod.dependencies.isNotEmpty()) {
                section[Attributes.Name("OML-Dependencies")] = mod.dependencies.joinToString(", ") { it.display }
            }
            manifest.entries[mod.id] = section
        }

        JarOutputStream(output.outputStream().buffered(), manifest).use { out ->
            JarFile(source).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "META-INF/MANIFEST.MF") continue
                    val copy = JarEntry(entry.name).apply { time = entry.time }
                    out.putNextEntry(copy)
                    if (!entry.isDirectory) jar.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}
