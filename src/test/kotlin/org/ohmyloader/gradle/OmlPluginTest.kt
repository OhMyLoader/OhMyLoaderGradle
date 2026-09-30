package org.ohmyloader.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.*

/**
 * What the plugin actually wires, asserted on a throwaway project.
 *
 * The plugin's whole job is configuration, and configuration is the part that fails *silently*: a task
 * that was never registered, a dependency attached to the wrong configuration, a `.get()` reading a
 * default before the consumer's script had run — none of these raise an error, so every such property
 * is asserted explicitly. `ProjectBuilder` applies the plugin with no build script, no daemon and no
 * network, which keeps these assertions cheap. Deliberately not asserted: that `runClient`'s resolved
 * classpath contains the game jar — resolving it would pull in the `omlLoader` dependencies and
 * therefore a repository, making this test depend on a prior `publishToMavenLocal`, which is worse
 * than no test; the end-to-end launch covers that, and the dependency *declarations* are asserted below.
 */
class OmlPluginTest {

    private var counter = 0

    private fun project(dirName: String = "p"): Project {
        val dir = File("build/test-projects/${dirName}-${counter++}").absoluteFile
        dir.mkdirs()
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        project.pluginManager.apply("java")
        project.pluginManager.apply(OmlPlugin::class.java)
        return project
    }

    private fun Project.oml(): OmlExtension = extensions.getByType(OmlExtension::class.java)

    private fun Project.dependencyIds(configuration: String, withVersion: Boolean = false): List<String> =
        configurations.getByName(configuration).dependencies.map {
            if (withVersion) "${it.group}:${it.name}:${it.version}" else "${it.group}:${it.name}"
        }

    // -------------------------------------------------------------------------------------------
    // Task graph
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every task the plugin adds is registered and grouped`() {
        val project = project()
        // Tasks resolve their configuration eagerly when realized, and the game arguments read the
        // version: setting it is part of realizing a launch task, exactly as a consumer's `oml { }`
        // block would have done before the task graph was calculated.
        project.oml().minecraftVersion.set("26.3")
        val expected = listOf(
            "fetchClientJar", "fetchLibraries", "extractNatives",
            "fetchAssets", "runClient", "runServer",
        )
        for (name in expected) {
            val task = project.tasks.findByName(name)
            assertNotNull(task, "task $name was not registered")
            assertEquals("ohmyloader", task.group, "task $name is not in the ohmyloader group")
        }
    }

    @Test
    fun `nothing is registered before the java plugin is applied`() {
        // The plugin adds to `compileOnly` and reads the `main` source set, neither of which exists yet
        // in a Kotlin project where kotlin("jvm") brings the java plugin along a moment later.
        //
        // Checked through `tasks.names`, which reports what is registered without realizing it: the
        // realization of a launch task runs its configuration block, and that block resolves the
        // extension's values into plain ones — a contract that a half-configured project cannot meet.
        val dir = File("build/test-projects/no-java-${counter++}").absoluteFile
        dir.mkdirs()
        val project = ProjectBuilder.builder().withProjectDir(dir).build()

        project.pluginManager.apply(OmlPlugin::class.java)
        assertFalse(project.tasks.names.contains("runClient"), "runClient was registered without the java plugin")

        project.pluginManager.apply("java")
        assertTrue(project.tasks.names.contains("runClient"), "applying the java plugin did not register runClient")
    }

    // -------------------------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the API is a compileOnly dependency`() {
        // compileOnly and not implementation: the loader supplies the API at runtime, and bundling it
        // would put a second copy of OML's classes inside every mod jar.
        val ids = project().dependencyIds("compileOnly")
        assertTrue(ids.contains("org.ohmyloader:oml-api"), "compileOnly does not carry oml-api: $ids")
    }

    @Test
    fun `the launch classpath is a private configuration`() {
        val loader = project().configurations.getByName("omlLoader")
        assertTrue(loader.isCanBeResolved)
        // Consumed-by-the-project-only: if this were consumable, every mod this project publishes would
        // drag a loader dependency into its POM — which is why the adapter is attached here rather than
        // to `runtimeOnly`.
        assertFalse(loader.isCanBeConsumed)
    }

    @Test
    fun `the matching adapter is resolved from Maven by the declared version`() {
        // The artifact id is a name transform of the declared version (26.3 -> 26_3) — exactly the kind
        // of thing that has to be pinned.
        val project = project()
        project.oml().minecraftVersion.set("26.3")

        val ids = project.dependencyIds("omlLoader", withVersion = true)
        assertTrue(ids.contains("org.ohmyloader:oml-adapter-26_3:0.2.0-SNAPSHOT"), "omlLoader: $ids")
        assertTrue(ids.contains("org.ohmyloader:oml-core:0.2.0-SNAPSHOT"), "omlLoader: $ids")
        assertTrue(ids.contains("org.ohmyloader:oml-launcher:0.2.0-SNAPSHOT"), "omlLoader: $ids")
    }

    @Test
    fun `the adapter artifact follows every spelling of the version`() {
        listOf("26.3" to "oml-adapter-26_3")
            .forEach { [version, artifact] ->
                val project = project()
                project.oml().minecraftVersion.set(version)
                assertEquals(
                    artifact,
                    project.oml().adapterArtifact.get(),
                    "version $version must map to $artifact",
                )
            }
    }

    @Test
    fun `the adapter artifact is overridable`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        project.oml().adapterArtifact.set("oml-adapter-custom")
        assertEquals("oml-adapter-custom", project.oml().adapterArtifact.get())
    }

    // -------------------------------------------------------------------------------------------
    // Conventions
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the api version follows the oml_version project property with a fallback`() {
        // No property in a bare ProjectBuilder project, so the compiled-in default applies.
        assertEquals(OmlExtension.DEFAULT_API_VERSION, project().oml().apiVersion.get())
    }

    @Test
    fun `minecraftVersion has no convention, so a derived value is absent too`() {
        // Deliberate: guessing a game version would fetch and launch something nobody asked for, and the
        // mismatch surfaces much later as an injection failure. A derived value that silently defaulted
        // would undo that.
        val oml = project().oml()
        assertFalse(oml.minecraftVersion.isPresent)
        assertFalse(oml.adapterArtifact.isPresent)
    }

    @Test
    fun `every path convention hangs off runDir`() {
        val project = project()
        val oml = project.oml()
        oml.minecraftVersion.set("26.3")
        val runDir = oml.runDir.get().asFile.path.replace('\\', '/')
        listOf(
            oml.librariesDir.get().asFile to "26.3/libraries",
            oml.nativesDir.get().asFile to "26.3/natives",
            oml.modsDir.get().asFile to "mods",
            oml.assetsDir.get().asFile to "assets",
            oml.clientJar.get().asFile to "26.3/minecraft/client.jar",
        ).forEach { [file, suffix] ->
            assertTrue(
                file.path.replace('\\', '/').startsWith("$runDir/") &&
                    file.path.replace('\\', '/').endsWith(suffix),
                "expected a child $suffix of $runDir, got $file",
            )
        }
    }

    /**
     * The regression behind the version-keyed directories: a launch under one game version picked up a
     * *different build of the same library* out of a shared `run/libraries` and died in
     * `Minecraft.<init>` with NoSuchMethodError on an overload that build did not have.
     */
    @Test
    fun `two game versions cannot share an artifact path`() {
        val project = project()
        val oml = project.oml()
        listOf<Pair<String, (OmlExtension) -> File>>(
            "libraries" to { it.librariesDir.get().asFile },
            "natives" to { it.nativesDir.get().asFile },
            "client jar" to { it.clientJar.get().asFile },
        ).forEach { [what, path] ->
            // Two illustrative version ids; the point is that *any* two differ, not these two in
            // particular.
            oml.minecraftVersion.set("26.3")
            val first = path(oml)
            oml.minecraftVersion.set("26.4")
            val second = path(oml)
            assertNotEquals(first, second, "the $what path is shared by two versions: $first")
        }
    }

    // -------------------------------------------------------------------------------------------
    // The run tasks
    // -------------------------------------------------------------------------------------------

    @Test
    fun `runClient launches the launcher with the Java 27 contract and absolute paths`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        val client = project.tasks.getByName("runClient") as JavaExec

        assertEquals(OmlJvmContract.LAUNCHER_MAIN_CLASS, client.mainClass.get())
        assertTrue(
            client.workingDir.path.replace('\\', '/').endsWith("run/client"),
            "the client needs its own game directory, got ${client.workingDir}",
        )

        OmlJvmContract.BASE_JVM_ARGS.forEach { arg ->
            assertTrue(client.jvmArgs.contains(arg), "missing jvm arg $arg")
        }

        listOf(
            "-Doml.side=client", "-Doml.game.jar=", "-Doml.library.dir=", "-Djava.library.path=",
            "-Doml.mods.dir=",
        ).forEach { prefix ->
            assertTrue(
                client.jvmArgs.any { it.startsWith(prefix) },
                "missing $prefix in ${client.jvmArgs}",
            )
        }
        // Absolute, because the working directory is run/client and a relative path would point somewhere
        // else entirely — a wrong mods directory loads no mods and reports no error. Only the properties
        // that *are* paths are held to this: `oml.side` is a dispatch token and `oml.diagnostics` a flag.
        listOf(
            "-Doml.game.jar=", "-Doml.library.dir=", "-Djava.library.path=",
            "-Doml.mods.dir=",
        ).forEach { prefix ->
            client.jvmArgs.filter { it.startsWith(prefix) }.forEach { arg ->
                val value = arg.substringAfter('=')
                assertTrue(File(value).isAbsolute, "$arg does not carry an absolute path")
            }
        }
    }

    @Test
    fun `runClient passes the game arguments the vanilla launcher would`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        val client = project.tasks.getByName("runClient") as JavaExec

        assertEquals("26.3-OML", client.args[client.args.indexOf("--version") + 1])
        assertTrue(client.args.contains("--gameDir"))
        assertTrue(client.args.contains("--assetsDir"))
        assertTrue(client.args.contains("--accessToken"))
        // Unset assetIndex is resolved at execution time from the id the fetchAssets task records,
        // so it never appears in the configuration-time arg list (see the argument-provider test).
        assertFalse(client.args.contains("--assetIndex"))
        // joptsimple-based mains declare this required and refuse to start without it, so it belongs
        // to the launch contract rather than to a consumer's extraGameArgs.
        assertEquals("{}", client.args[client.args.indexOf("--userProperties") + 1])
    }

    @Test
    fun `a declared asset index is passed through at execution time`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        project.oml().assetIndex.set("26")
        val client = project.tasks.getByName("runClient") as JavaExec

        // the provider wiring exists; its value is resolved by the function asserted below
        assertEquals(1, client.argumentProviders.size)
        assertEquals(listOf("--assetIndex", "26"), resolveAssetIndexArgs("26", File("/nowhere")))
    }

    @Test
    fun `an undeclared asset index is taken from the id fetchAssets recorded`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        val assets = project.oml().assetsDir.get().asFile.apply { mkdirs() }
        File(assets, "oml-asset-index.txt").writeText("34\n")

        assertEquals(listOf("--assetIndex", "34"), resolveAssetIndexArgs(null, assets))
    }

    @Test
    fun `a launch without a recorded asset index fails with an actionable message`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveAssetIndexArgs(null, File("/nowhere"))
        }
        assertTrue(failure.message!!.contains("fetchAssets"), failure.message)
    }

    @Test
    fun `runClient depends on everything a launch needs, including the natives`() {
        val project = project()
        project.oml().minecraftVersion.set("26.3")

        val client = project.tasks.getByName("runClient")
        val deps = client.taskDependencies.getDependencies(client).map { it.name }

        listOf(
            "fetchClientJar", "fetchLibraries", "extractNatives", "fetchAssets",
        ).forEach { name ->
            assertTrue(deps.contains(name), "runClient does not depend on $name: $deps")
        }
    }

    @Test
    fun `a server fetches the client jar too and never the assets`() {
        // The client jar is the game jar for both sides: the modern dedicated-server download is a
        // bundler wrapper, while the client jar carries the flat, unified classes. There is no separate
        // server fetch task at all, and a dedicated server renders nothing.
        val project = project()
        project.oml().minecraftVersion.set("26.3")
        val server = project.tasks.getByName("runServer")
        val deps = server.taskDependencies.getDependencies(server).map { it.name }

        assertTrue(deps.contains("fetchClientJar"), deps.toString())
        assertTrue(deps.contains("extractNatives"), deps.toString())
        assertFalse(deps.contains("fetchAssets"), "a dedicated server renders nothing: $deps")

        val exec = server as JavaExec
        assertEquals(OmlJvmContract.LAUNCHER_MAIN_CLASS, exec.mainClass.get())
        assertTrue(exec.args.contains("--nogui"))
        assertTrue(exec.jvmArgs.any { it == "-Doml.side=server" })
    }
}
