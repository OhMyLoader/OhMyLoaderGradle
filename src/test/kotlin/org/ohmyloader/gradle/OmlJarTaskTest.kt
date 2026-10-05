package org.ohmyloader.gradle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * The distributable-jar contract: the built jar gains OML metadata in its manifest (per-mod
 * sections for version and dependencies), keeps every original entry, and is named after the
 * declared mod id and version. A jar with no entry point, or with two @Mod classes claiming one
 * id, is refused with a readable error — the same @Mod contract the loader enforces.
 */
class OmlJarTaskTest {

    private fun project(): Project =
        ProjectBuilder.builder().withProjectDir(File(createTempDirectory("omljar").toFile(), "p")).build()

    /** Builds a mod jar through ASM: one @Mod class plus a marker resource entry. */
    private fun modJar(
        file: File,
        modId: String,
        version: String,
        dependencies: List<String> = emptyList(),
    ) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "org/example/TestMod", null, "java/lang/Object", null)
        val annotation = writer.visitAnnotation("Lorg/ohmyloader/api/Mod;", true)
        annotation.visit("id", modId)
        annotation.visit("version", version)
        if (dependencies.isNotEmpty()) {
            val array = annotation.visitArray("dependencies")
            for (dep in dependencies) array.visit(null, dep)
            array.visitEnd()
        }
        annotation.visitEnd()
        writer.visitEnd()

        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("org/example/TestMod.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("assets/testmod/marker.txt"))
            jar.write("present".toByteArray())
            jar.closeEntry()
        }
    }

    private fun runTask(source: File, projectName: String = "testmod"): Pair<OmlJarTask, File> {
        val project = project()
        val task = project.tasks.create("omlJar", OmlJarTask::class.java)
        task.jarFile.set(source)
        task.outputDirectory.set(project.layout.buildDirectory.dir("omlJar"))
        task.projectName.set(projectName)
        task.projectVersion.set("9.9.9")
        task.run()
        val outputs = project.layout.buildDirectory.dir("omlJar").get().asFile.listFiles()!!
        return task to outputs.single()
    }

    @Test
    fun `produces a named jar with per-mod manifest sections and every original entry`() {
        val source = File(createTempDirectory("omljar-src").toFile(), "built.jar")
        modJar(source, modId = "testmod", version = "0.1.0", dependencies = listOf("other@>=1.0"))

        val (_, output) = runTask(source)

        assertEquals("testmod-0.1.0.jar", output.name, "the single-mod name carries the id and version")
        java.util.jar.JarFile(output).use { jar ->
            val main = jar.manifest.mainAttributes
            assertEquals("testmod", main.getValue(java.util.jar.Attributes.Name("OML-Mod-Id")))
            val section = jar.manifest.entries["testmod"]!!
            assertEquals("0.1.0", section.getValue(java.util.jar.Attributes.Name("OML-Mod-Version")))
            assertEquals("other@>=1.0", section.getValue(java.util.jar.Attributes.Name("OML-Dependencies")))
            assertNotNull(jar.getEntry("org/example/TestMod.class"), "classes survive the repack")
            assertNotNull(jar.getEntry("assets/testmod/marker.txt"), "resources survive the repack")
            // the output carries exactly one manifest — the new one, with the OML attributes
            val manifestEntry = jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue("OML-Mod-Id" in manifestEntry, "the shipped manifest must carry the OML metadata")
        }
    }

    @Test
    fun `a jar without an entry point is refused with a readable error`() {
        val empty = File(createTempDirectory("omljar-src").toFile(), "empty.jar")
        JarOutputStream(empty.outputStream(), Manifest()).use { jar ->
            jar.putNextEntry(JarEntry("assets/only.txt"))
            jar.write("x".toByteArray())
            jar.closeEntry()
        }

        val error = assertFailsWith<IllegalStateException> { runTask(empty).first.run() }
        assertTrue("no @Mod-annotated class" in error.message!!, "actual: ${error.message}")
    }

    @Test
    fun `one jar carrying several mods falls back to the project name`() {
        val source = File(createTempDirectory("omljar-src").toFile(), "built.jar")
        JarOutputStream(source.outputStream()).use { jar ->
            for (id in listOf("mod_a", "mod_b")) {
                val writer = ClassWriter(0)
                val internal = "org/example/${id.capitalize()}"
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
                writer.visitAnnotation("Lorg/ohmyloader/api/Mod;", true).apply {
                    visit("id", id)
                    visit("version", "2.0")
                    visitEnd()
                }
                writer.visitEnd()
                jar.putNextEntry(JarEntry("$internal.class"))
                jar.write(writer.toByteArray())
                jar.closeEntry()
            }
        }

        val (_, output) = runTask(source, projectName = "multi")

        assertEquals("multi-9.9.9.jar", output.name, "several mods share the project-named distribution")
        val read = java.util.jar.JarFile(output)
        read.use { jar ->
            assertEquals("mod_a, mod_b", jar.manifest.mainAttributes.getValue(java.util.jar.Attributes.Name("OML-Mod-Id")))
        }
    }
}
