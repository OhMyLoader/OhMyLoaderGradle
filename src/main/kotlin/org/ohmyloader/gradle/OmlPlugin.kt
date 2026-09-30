package org.ohmyloader.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.ohmyloader.devtools.AssetDownloader
import java.io.File

/**
 * `org.ohmyloader.gradle` — build support for an OhMyLoader mod. Applying it installs the [OmlExtension]
 * (`oml { ... }`) with conventions for every path, puts `org.ohmyloader:oml-api` on the project's
 * `compileOnly` classpath (so mod sources can use the loader's types without the loader being bundled
 * into the mod jar), and registers the tasks a development run needs: the fetch tasks — `fetchClientJar`,
 * `fetchLibraries`, `extractNatives`, `fetchAssets` — all downloading **in-process** through the typed
 * `oml-devtools` API, and `runClient` / `runServer`, which launch the game through `OMLBootstrap` with
 * the JVM contract OML requires. The client jar is the game jar for both sides: the modern server
 * download is a bundler wrapper, while the client jar carries the flat, unified classes.
 */
class OmlPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val oml = project.extensions.create("oml", OmlExtension::class.java)
        installConventions(project, oml)

        // Keyed off the Java plugin rather than applied unconditionally: this plugin adds to the
        // `compileOnly` configuration and uses the `main` source set, neither of which exists yet at
        // apply time in a Kotlin project where `kotlin("jvm")` brings the Java plugin along slightly
        // later. `java-library` extends `java`, so one hook covers both.
        project.plugins.withType(JavaPlugin::class.java) {
            attachApiDependency(project, oml)
            val fetch = registerFetchTasks(project, oml)
            registerRunTasks(project, oml, fetch)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Conventions
    // ---------------------------------------------------------------------------------------------

    /**
     * Every default, in one place, so a consumer can see the full set by reading one function.
     *
     * All of these are `convention(...)` rather than `set(...)`: a convention loses to anything the
     * consumer declares, which is the whole reason the extension's fields are queryable properties.
     * Note that this runs inside `plugins { }` — before the rest of the consumer's script — so nothing
     * here may *read* a field.
     */
    private fun installConventions(project: Project, oml: OmlExtension) {
        oml.apiVersion.convention(
            project.providers.gradleProperty("oml_version")
                .filter { it.isNotBlank() }
                .orElse(OmlExtension.DEFAULT_API_VERSION),
        )
        oml.heap.convention("")
        oml.extraGameArgs.convention(emptyList())
        oml.diagnostics.convention("")

        oml.runDir.convention(project.layout.projectDirectory.dir("run"))

        // The artifact directories are keyed by game version; `run/assets` and `run/mods` below are not.
        // Two versions can each legitimately hold a *different build of the same library*: OMLCore puts
        // every jar it finds under librariesDir on the runtime search path (`buildRuntimeUrls`) and
        // whichever sorts first wins, so a shared `libraries/` can kill a newer launch with
        // NoSuchMethodError inside `Minecraft.<init>`. `fetchClientJar` only checks that the file exists,
        // so a shared client.jar path would silently launch whichever version was downloaded first. A
        // version id in the *file name* would fix neither — the stale jar stays on the search path —
        // hence the per-version directory.
        val versionDir = oml.minecraftVersion.flatMap { version -> oml.runDir.dir(version) }
        oml.librariesDir.convention(versionDir.map { it.dir("libraries") })
        oml.nativesDir.convention(versionDir.map { it.dir("natives") })
        // The zig project, and the build tree inside it. The second follows the first so a consumer
        // that relocates the zig project gets the deploy path right by relocating one property.
        oml.nativeProjectDir.convention(project.rootProject.layout.projectDirectory.dir("oml-native"))
        oml.omlNativeBuildDir.convention(oml.nativeProjectDir.map { it.dir("build") })
        oml.clientJar.convention(versionDir.map { it.file("minecraft/client.jar") })
        oml.modsDir.convention(oml.runDir.dir("mods"))
        oml.assetsDir.convention(oml.runDir.dir("assets"))

        oml.adapterArtifact.convention(
            oml.minecraftVersion.map { version -> "oml-adapter-" + version.replace('.', '_') },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The API dependency
    // ---------------------------------------------------------------------------------------------

    /**
     * `compileOnly("org.ohmyloader:oml-api:<version>")`.
     *
     * `compileOnly`, not `implementation`: the API is supplied by the loader at runtime, and bundling it
     * would put a second copy of OML's classes inside the mod jar, where the two copies would disagree
     * the moment either is updated independently. Added through `addProvider` so the version is read
     * when the configuration resolves — this runs during `plugins { }`, before the consumer has had any
     * chance to set `apiVersion`.
     */
    private fun attachApiDependency(project: Project, oml: OmlExtension) {
        project.dependencies.addProvider(
            "compileOnly",
            oml.apiVersion.map { "org.ohmyloader:oml-api:$it" },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Fetching
    // ---------------------------------------------------------------------------------------------

    private class FetchTasks(
        val clientJar: TaskProvider<OmlFetchClientJar>,
        val libraries: TaskProvider<OmlFetchLibraries>,
        val natives: TaskProvider<OmlExtractNatives>,
        val assets: TaskProvider<OmlFetchAssets>,
    )

    private fun registerFetchTasks(project: Project, oml: OmlExtension): FetchTasks {
        val clientJar = project.tasks.register("fetchClientJar", OmlFetchClientJar::class.java) { task ->
            task.description =
                "Ensure the Minecraft client jar is present and passes Piston's size and SHA-1 checks"
            task.minecraftVersion.set(oml.minecraftVersion)
            task.clientJar.set(oml.clientJar)
        }

        val libraries = project.tasks.register("fetchLibraries", OmlFetchLibraries::class.java) { task ->
            task.description =
                "Download every runtime library the version JSON declares for this platform"
            task.minecraftVersion.set(oml.minecraftVersion)
            task.librariesDir.set(oml.librariesDir)
        }

        // The published oml-native library (`org.ohmyloader:oml-native:<apiVersion>:natives-<os>`):
        // the zero-config default source of `extractNatives`'s oml-native deploy. Resolved leniently —
        // an empty view (coordinate not published to any of the consumer's repositories) is the
        // plugin's documented degrade-to-vanilla-zlib path, not a failure. A consumer overriding
        // `oml.omlNativeBuildDir` still wins: the task prefers the local zig build over this artifact.
        val omlNative = project.configurations.create("omlNative") { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.description =
                "The published oml-native library jar (natives-<os> classifier) for the running platform"
        }
        project.dependencies.addProvider(
            omlNative.name,
            oml.apiVersion.map { version ->
                // The Maven classifier token from the JVM's platform. `buildPlatforms` speaks the
                // build-tree spelling (macosx), so macOS maps back to the repository's `osx`; the
                // architecture picks the slot — an arm64 JVM cannot load an x86_64 library, so it
                // resolves `natives-<os>-arm64` (the plain jar is the x86_64 one, the spelling
                // legacy launcher rules resolve).
                val os = OmlNativeLayout.buildPlatforms().first()
                    .let { if (it == "macosx") "osx" else it }
                val isArm = OmlNativeLayout.buildArchs().first() in setOf("arm64", "aarch64")
                "org.ohmyloader:oml-native:$version:natives-$os" + if (isArm) "-arm64" else ""
            },
        )

        val natives = project.tasks.register("extractNatives", OmlExtractNatives::class.java) { task ->
            task.description =
                "Unpack the natives of the running platform into the directory java.library.path will point at"
            task.minecraftVersion.set(oml.minecraftVersion)
            task.librariesDir.set(oml.librariesDir)
            task.nativesDir.set(oml.nativesDir)
            task.omlNativeBuildDir.set(oml.omlNativeBuildDir)
            task.nativeJars.setFrom(omlNative.incoming.artifactView { it.lenient(true) }.files)
            // Explicit rather than inferred: the natives jars are located by reading the version JSON, so
            // this task needs the libraries present but cannot express that as an input without also
            // fingerprinting hundreds of megabytes on every run.
            task.dependsOn(libraries)
        }

        // Deferred to `afterEvaluate`, unlike everything else here, because the decision is a file check
        // and the location is a consumer's to choose: `oml { nativeProjectDir.set(...) }` cannot run
        // before `plugins { }` has applied this plugin, so an eager read would only ever see the
        // convention. When the zig project is present, `buildOmlNative` builds as a normal incremental
        // task ahead of `extractNatives`; an external mod project — the common case — has no `build.zig`
        // where the default points and simply never gets the task (the deploy keeps its logged skip).
        project.afterEvaluate {
            val nativeProject = oml.nativeProjectDir.get().asFile
            if (!File(nativeProject, "build.zig").isFile) return@afterEvaluate
            val buildNative = project.tasks.register("buildOmlNative", OmlBuildNative::class.java) { task ->
                task.group = "ohmyloader"
                task.description = "Rebuild the oml-native library with zig (skipped when sources are unchanged)"
                task.nativeProjectDir.set(nativeProject)
                task.buildOutputDir.set(File(nativeProject, "build"))
                task.sourceFiles.from(project.fileTree(File(nativeProject, "src")))
                task.sourceFiles.from(File(nativeProject, "build.zig"))
                task.sourceFiles.from(File(nativeProject, "build.zig.zon"))
            }
            natives.configure { it.dependsOn(buildNative) }
        }

        val assets = project.tasks.register("fetchAssets", OmlFetchAssets::class.java) { task ->
            task.description =
                "Validate and download the game assets (objects/ and indexes/ of the version's index)"
            task.minecraftVersion.set(oml.minecraftVersion)
            task.assetsDir.set(oml.assetsDir)
        }

        return FetchTasks(clientJar, libraries, natives, assets)
    }

    // ---------------------------------------------------------------------------------------------
    // Running
    // ---------------------------------------------------------------------------------------------

    private fun registerRunTasks(project: Project, oml: OmlExtension, fetch: FetchTasks) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.named("main")

        // A dedicated configuration rather than `runtimeOnly(...)` on the project: the loader is needed
        // to *launch* and must not become part of what a consumer publishes. `oml-launcher` declares its
        // dependency on `oml-core` as compileOnly (it is meant to be embedded in the installer fat jar),
        // so oml-core has to be requested alongside it or the launcher would find no loader to start.
        val loader = project.configurations.create("omlLoader") { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.description =
                "The OhMyLoader runtime (oml-core and oml-launcher) the run tasks launch"
        }
        project.dependencies.addProvider(loader.name, oml.apiVersion.map { "org.ohmyloader:oml-core:$it" })
        project.dependencies.addProvider(loader.name, oml.apiVersion.map { "org.ohmyloader:oml-launcher:$it" })

        // The per-version adapter, resolved from Maven by the version the project declared. Deliberately
        // on omlLoader and not on runtimeOnly: the latter would put a loader dependency into whatever this
        // project publishes, so every consuming install would inherit a per-version pin it never asked
        // for. omlLoader is canBeConsumed = false -- the launch runtime is this project's own business.
        project.dependencies.addProvider(
            loader.name,
            oml.adapterArtifact.flatMap { adapter -> oml.apiVersion.map { "org.ohmyloader:$adapter:$it" } },
        )

        val omlRuntime = project.files(main.map { it.output }, main.map { it.runtimeClasspath }, loader)

        registerClient(project, oml, fetch, omlRuntime)
        registerServer(project, oml, fetch, omlRuntime)
    }

    private fun registerClient(
        project: Project,
        oml: OmlExtension,
        fetch: FetchTasks,
        runtime: org.gradle.api.file.FileCollection,
    ) {
        project.tasks.register("runClient", org.gradle.api.tasks.JavaExec::class.java) { task ->
            task.group = "ohmyloader"
            task.description = "Launch the Minecraft client with OML in client mode"
            task.dependsOn(fetch.clientJar, fetch.libraries, fetch.natives, fetch.assets)
            // The vanilla jar goes on the *launch classpath*, not merely into a -D property. OMLCore
            // builds its loader search path from java.class.path plus a recursive walk of
            // oml.library.dir; `-Doml.game.jar` only records which jar is the game and is never searched.
            // The adapter convention gets this for free because it keeps the client jar inside
            // its own libs/ directory — which *is* oml.library.dir — so a layout that separates them, like
            // this one, has to add it explicitly or the launch dies with
            // "entry class net.minecraft.client.main.Main not found".
            task.classpath = project.files(runtime, oml.clientJar)
            task.mainClass.set(OmlJvmContract.LAUNCHER_MAIN_CLASS)

            // The client keeps its own game directory (logs, saves, options.txt); the shared paths —
            // libraries, natives, mods, cache — are passed as absolute -D properties because the working
            // directory is no longer where they live.
            val gameDir = oml.runDir.dir("client").get().asFile
            task.workingDir = gameDir

            val clientJar = oml.clientJar.get().asFile
            val allJvmArgs = OmlJvmContract.jvmArgs(oml.heap.orNull) +
                runtimeProperties(oml, gameJar = clientJar, side = "client")
            // One at a time: JavaForkOptions offers jvmArgs(String...), jvmArgs(Iterable) and
            // jvmArgs(Object...), and passing a List as a single argument is ambiguous across them.
            allJvmArgs.forEach { task.jvmArgs(it) }
            oml.extraJvmArgs.orNull.orEmpty().forEach { task.jvmArgs(it) }

            gameArgs(oml, gameDir).forEach { task.args(it) }

            // --assetIndex is resolved at execution time (see resolveAssetIndexArgs): the explicit value
            // when one is declared, otherwise the id the fetchAssets task recorded in the assets
            // directory. The two candidate values are captured here, at configuration time; only the
            // *file read* waits for execution, because on a first build the marker is written by this
            // task's own fetchAssets dependency. Launching without the arg is not an option: the game
            // treats it as "no asset index" and silently serves nothing from the index objects (in
            // 26.3, where textures and lang ship in the jar, that surfaces as nothing but "no sounds").
            val explicitAssetIndex = oml.assetIndex.orNull?.takeIf { it.isNotBlank() }
            val assetsDirForIndex = oml.assetsDir.get().asFile
            task.argumentProviders.add { resolveAssetIndexArgs(explicitAssetIndex, assetsDirForIndex) }

            // Everything the execution actions need is resolved here, at configuration time, into plain
            // values. A task action that reached back into the extension or the project would make the
            // configuration cache refuse to serialize the task — which is the same rule the adapter
            // convention documents, and the reason this block captures a `File` rather than a `Provider`.
            val gameDirToCreate = gameDir
            val modsDirectory = oml.modsDir.get().asFile
            // The project's own jar *is* the mod: build it as part of the launch and drop it into the
            // mods directory, so a mod project needs no copy task of its own. Resolved here, at
            // configuration time, into a plain File — the same rule the rest of the block follows, and
            // the jar's path is static (it derives from the project layout, not from user config).
            val modJar = project.tasks.named("jar", Jar::class.java)
            task.dependsOn(modJar)
            val modJarFile = modJar.get().archiveFile.get().asFile
            // One action, not two `doFirst` calls. Gradle *prepends* them, so of two handlers the second
            // one registered runs first — which silently inverts the order the code reads. Splitting
            // "create the directory" from "write into the directory" is exactly how that bites.
            task.doFirst { t ->
                gameDirToCreate.mkdirs()
                val deployed = File(modsDirectory, modJarFile.name)
                deployModJar(modJarFile, deployed)
                t.logger.lifecycle("[oml] mods directory: ${modsDirectory.absolutePath}")
                t.logger.lifecycle("[oml] mod deployed: ${deployed.name}")
            }
        }
    }

    /**
     * Deploys the mod jar into the run's mods directory, skipping the overwrite when the
     * destination already holds identical bytes. The client and the server share one mods
     * directory and each live JVM holds its copy open — on Windows an open jar cannot be deleted,
     * so a blind overwrite makes the *second* launch of the pair fail with
     * `FileAlreadyExistsException`. Same bytes = same mod; skipping is always correct here, and a
     * genuinely changed jar still overwrites (the other side must be stopped first, as with any
     * hot redeploy).
     */
    private fun deployModJar(source: File, destination: File) {
        val identical = destination.isFile &&
            destination.length() == source.length() &&
            destination.inputStream().use { a ->
                source.inputStream().use { b ->
                    a.readBytes().contentEquals(b.readBytes())
                }
            }
        if (!identical) {
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun registerServer(

        project: Project,
        oml: OmlExtension,
        fetch: FetchTasks,
        runtime: org.gradle.api.file.FileCollection,
    ) {
        project.tasks.register("runServer", org.gradle.api.tasks.JavaExec::class.java) { task ->
            task.group = "ohmyloader"
            task.description = "Launch the dedicated Minecraft server with OML in server mode"
            task.dependsOn(fetch.clientJar, fetch.libraries, fetch.natives)
            task.mainClass.set(OmlJvmContract.LAUNCHER_MAIN_CLASS)

            val gameDir = oml.runDir.dir("server").get().asFile
            task.workingDir = gameDir

            // The client jar *is* the server jar. The modern dedicated-server download is a bundler wrapper
            // (its `server.jar` holds only `net/minecraft/bundler/Main` and the real jar nested under
            // `META-INF/versions/`), so pointing a launch at it fails in the loader with `entry class
            // net.minecraft.server.Main not found` — which reads like a broken adapter or a missing
            // download rather than "the wrong jar was chosen". The client jar carries the flat classes.
            val gameJar = oml.clientJar.get().asFile
            // Same reason as the client: see the comment there.
            task.classpath = project.files(runtime, gameJar)

            // Server-only, deliberately outside the contract: pre-committing the whole heap at start
            // costs a slower boot in exchange for steady mid-tick latency, a trade a client should not
            // make and a flag the launcher manifest cannot carry anyway.
            val allJvmArgs = OmlJvmContract.jvmArgs(oml.heap.orNull) +
                listOf("-XX:+AlwaysPreTouch") +
                runtimeProperties(oml, gameJar = gameJar, side = "server")
            allJvmArgs.forEach { task.jvmArgs(it) }
            oml.extraJvmArgs.orNull.orEmpty().forEach { task.jvmArgs(it) }

            task.args("--nogui")

            val eulaFile = File(gameDir, "eula.txt")
            // The project's own jar is the mod; deploy it exactly as the client does. The mods
            // directory is shared between the two sides, so a server launch sees the same jar.
            val modsDirectory = oml.modsDir.get().asFile
            val modJar = project.tasks.named("jar", Jar::class.java)
            task.dependsOn(modJar)
            val modJarFile = modJar.get().archiveFile.get().asFile
            // One action, and here it is load-bearing: Gradle *prepends* `doFirst` handlers, so a second
            // one registered after this would run *before* it, and the write would fail with a bare
            // FileNotFoundException because the directory it writes into does not exist yet.
            //
            // A first server start refuses to run before the EULA is accepted. Writing it here is for
            // local development only, and only because the person running this task is the one deciding;
            // a real deployment is expected to read and accept https://aka.ms/MinecraftEULA by hand.
            task.doFirst {
                gameDir.mkdirs()
                eulaFile.writeText("eula=true\n")
                deployModJar(modJarFile, File(modsDirectory, modJarFile.name))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The JVM contract, path half
    // ---------------------------------------------------------------------------------------------

    /**
     * The `-D` properties a launch needs, all with **absolute** paths: the working directory is not the
     * project directory (the client runs in `run/client`, the server in `run/server`), so anything
     * relative would silently point somewhere else — a wrong mods directory loads no mods and produces
     * no error. This is the Gradle-side copy of a contract that also exists launcher-side: the installer
     * writes the same properties into the version JSON it generates (`RuntimeProperties` in
     * `oml-installer`). `-Djava.library.path` is passed as a plain `-D` so it sits with the rest of the
     * resolved paths; the two forms are equivalent to the JVM.
     */
    private fun runtimeProperties(oml: OmlExtension, gameJar: File, side: String): List<String> =
        buildList {
            add("-Doml.side=$side")
            add("-Doml.game.jar=${gameJar.absolutePath}")
            add("-Doml.library.dir=${oml.librariesDir.get().asFile.absolutePath}")
            add("-Djava.library.path=${oml.nativesDir.get().asFile.absolutePath}")
            add("-Doml.mods.dir=${oml.modsDir.get().asFile.absolutePath}")
            oml.diagnostics.orNull?.takeIf { it.isNotBlank() }?.let { add("-Doml.diagnostics=$it") }
        }

    /** The game's own argument list — what the vanilla launcher would pass, no more. */
    private fun gameArgs(oml: OmlExtension, gameDir: File): List<String> =
        buildList {
            add("--version")
            add("${oml.minecraftVersion.get()}-OML")
            add("--gameDir")
            add(gameDir.absolutePath)
            add("--assetsDir")
            add(oml.assetsDir.get().asFile.absolutePath)
            // --assetIndex is deliberately NOT part of this list: its default — the id fetchAssets
            // resolved from the version metadata — does not exist until that task has run, which is
            // after this block. registerClient adds it through an argument provider evaluated at
            // execution time.
            add("--accessToken")
            add("0")
            // Not version-specific, despite appearances: joptsimple-based mains declare it *required* and
            // die with joptsimple.MissingRequiredOptionException before a single game class runs without
            // it. `{}` is what the vanilla launcher sends for an offline profile, which is exactly what
            // this is.
            add("--userProperties")
            add("{}")
            // Last, so a consumer's own requirement overrides anything OML supplied rather than being
            // overridden by it.
            addAll(oml.extraGameArgs.getOrElse(emptyList()))
        }
}

/**
 * The `--assetIndex` argument pair for a client launch.
 *
 * A top-level function rather than a [OmlPlugin] member on purpose: the argument provider that calls
 * it runs at task execution, and a lambda capturing the plugin instance (or any project state) makes
 * the configuration cache refuse to serialize the task — the same rule the run-task registration
 * documents. Two plain values in, one list out, so the tests can call it directly: the provider runs
 * too late in the task lifecycle for a test to observe, and its failure mode — a client that starts
 * and then plays no sounds — is exactly the kind this plugin exists to make loud.
 */
internal fun resolveAssetIndexArgs(explicitAssetIndex: String?, assetsDir: File): List<String> {
    if (explicitAssetIndex != null) {
        return listOf("--assetIndex", explicitAssetIndex)
    }
    val marker = File(assetsDir, AssetDownloader.ASSET_INDEX_MARKER)
    val id = marker.takeIf(File::isFile)?.readText()?.trim().orEmpty()
    require(id.isNotEmpty()) {
        "no asset index id recorded in $marker — run the fetchAssets task first. The " +
            "game cannot derive the id from its own jar, and launching without it " +
            "silently misses every asset-index-only resource (in 26.3: all sounds)."
    }
    return listOf("--assetIndex", id)
}
