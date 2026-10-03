package org.ohmyloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.ohmyloader.devtools.AssetDownloader
import org.ohmyloader.devtools.GameEnvironment
import java.io.File
import java.util.zip.ZipFile

/**
 * Shared behavior of the fetch tasks below. They never skip, and that is the point: each declares its
 * destination as an output so Gradle can wire the graph, then switches the up-to-date check off. The
 * downloader re-verifies an existing file against the size and SHA-1 in the version JSON before trusting
 * it; if Gradle short-circuited on "the file is there", a jar truncated by a killed process would be
 * accepted by every subsequent build forever. The cost is a local SHA-1 of the artifacts already present
 * — tens of milliseconds for a jar, around a second for the full library set.
 */
@DisableCachingByDefault(
    because = "The result is network state on disk, and the task deliberately re-runs so it can "
        + "re-verify what is already there instead of trusting a cache entry.",
)
internal abstract class OmlFetchTask : DefaultTask() {

    /** Declared so dependent tasks order correctly; the value is printed for the user's benefit. */
    @get:Input
    abstract val minecraftVersion: Property<String>

    init {
        group = "ohmyloader"
        outputs.upToDateWhen { false }
    }

    /**
     * Runs [body] against a fresh [GameEnvironment], with the downloader's narration routed into this
     * task's logger and the environment closed afterward.
     *
     * The sink is process-global, so it is installed and restored per call rather than for the build.
     * Tasks in one project run sequentially under Gradle's default execution, which is why a plain
     * save/restore is sufficient; a parallel execution mode would need a lock, and that is not a mode
     * this plugin claims to support yet.
     */
    protected fun <T> withEnvironment(body: (GameEnvironment) -> T): T {
        val version = minecraftVersion.orNull?.trim()
        require(!version.isNullOrEmpty()) {
            "oml.minecraftVersion must be set, e.g. oml { minecraftVersion.set(\"26.3\") }"
        }

        val previousLog = AssetDownloader.logSink
        val previousError = AssetDownloader.errorSink
        AssetDownloader.logSink = { logger.lifecycle("[oml] $it") }
        AssetDownloader.errorSink = { logger.error("[oml] $it") }
        try {
            return GameEnvironment.open(version).use(body)
        } finally {
            AssetDownloader.logSink = previousLog
            AssetDownloader.errorSink = previousError
        }
    }

    /**
     * Creates this path as a **directory**.
     *
     * Deliberately not named `ensureExists`: applied to a jar path, it would create a *directory* named
     * `client.jar`, and the download's atomic move onto that path would then fail on every retry with a
     * bare `...client.jar.tmp -> ...client.jar` that names neither cause nor fix. Two helpers with
     * unambiguous names make the mistake impossible to repeat by accident.
     */
    protected fun File.ensureDirectory(): File = apply { mkdirs() }

    /** Creates the parent of a path a task is about to write a file to. */
    protected fun File.ensureParentDirectory(): File = apply { parentFile?.mkdirs() }
}

/**
 * `fetchClientJar` — ensures the vanilla client jar is present and matches Piston's size and SHA-1.
 *
 * The jar is the input to every later stage: it is what the launch classpath ultimately resolves game
 * classes from. A corrupt one is not a "fails loudly" situation, it is a `NoClassDefFoundError`
 * from deep inside the game, so this task's job is verification more than download.
 */
@DisableCachingByDefault(
    because = "The result is network state on disk, and the task deliberately re-runs so it can "
        + "re-verify what is already there instead of trusting a cache entry.",
)
internal abstract class OmlFetchClientJar : OmlFetchTask() {

    @get:OutputFile
    abstract val clientJar: RegularFileProperty

    @TaskAction
    fun fetch() {
        // The parent, never the jar path itself: see ensureDirectory.
        val target = clientJar.get().asFile.ensureParentDirectory()
        withEnvironment { it.downloadClientJar(target) }
    }
}

/**
 * `fetchLibraries` — every runtime library the version JSON declares for this platform.
 *
 * This is also where the natives come from: in current version JSONs they travel as an ordinary library
 * artifact with a `natives-<platform>` classifier, so they are fetched here and unpacked by
 * [OmlExtractNatives], which is a separate task on purpose. Locating the natives jars by a directory
 * walk (names ending in `natives-windows.jar`) is not an option: it also matches jars belonging to a
 * *different* version installed side by side in the same directory.
 */
@DisableCachingByDefault(
    because = "The result is network state on disk, and the task deliberately re-runs so it can "
        + "re-verify what is already there instead of trusting a cache entry.",
)
internal abstract class OmlFetchLibraries : OmlFetchTask() {

    @get:OutputDirectory
    abstract val librariesDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val target = librariesDir.get().asFile.ensureDirectory()
        withEnvironment { it.downloadLibraries(target) }
    }
}

/**
 * `fetchAssets` — validates and downloads the game assets (`objects/`, `indexes/`).
 *
 * The asset index id is resolved from the version JSON rather than asked for: it is a property of the
 * version, and there is deliberately no `assetIndex` input here — one less way to fetch the wrong index
 * and end up with a client that starts and then finds no textures. Never skippable for the same reason
 * as the jars: an asset object is looked up by its own hash, so a corrupt one is unusable by
 * construction and re-verifying it is the only way to notice.
 */
@DisableCachingByDefault(
    because = "The result is network state on disk, and the task deliberately re-runs so it can "
        + "re-verify what is already there instead of trusting a cache entry.",
)
internal abstract class OmlFetchAssets : OmlFetchTask() {

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val target = assetsDir.get().asFile.ensureDirectory()
        withEnvironment { it.downloadAssets(target) }
    }
}

/**
 * `extractNatives` — unpacks the native libraries (`.dll` / `.dylib` / `.so`) of the platform the JVM
 * runs on into the directory `java.library.path` points at.
 *
 * Kept apart from [OmlFetchLibraries] so it can be requested, timed and fail on its own. A missing
 * native does not fail at start-up; the game dies later, inside LWJGL, with an `UnsatisfiedLinkError`
 * that says nothing about which directory was expected.
 */
@DisableCachingByDefault(
    because = "The result is network state on disk, and the task deliberately re-runs so it can "
        + "re-verify what is already there instead of trusting a cache entry.",
)
internal abstract class OmlExtractNatives : OmlFetchTask() {

    /**
     * Not `@InputDirectory`: this holds the whole game library set — hundreds of megabytes — and the task
     * never skips, so fingerprinting it every run would buy nothing and cost a full-tree scan. The
     * dependency is expressed by an explicit `dependsOn` at registration instead.
     */
    @get:Internal
    abstract val librariesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val nativesDir: DirectoryProperty

    /**
     * Root of the zig build tree for our own `oml-native` library (the layout the build tasks
     * deploy into). Optional: when it is unset or carries no build for the running platform, the
     * Maven artifact is tried next — see [nativeJars].
     */
    @get:Internal
    abstract val omlNativeBuildDir: DirectoryProperty

    /**
     * The `natives-<os>` classified jar of the published `org.ohmyloader:oml-native` coordinate
     * (library at the jar root), resolved from the consumer's repositories. Resolved leniently, so
     * a consumer whose repository has no published artifact (an older layer, a coordinate nobody
     * published yet) contributes an empty collection instead of failing the build. Tried after
     * [omlNativeBuildDir]: a developer pointing at a fresh zig build wants that build, not the
     * last published one.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nativeJars: ConfigurableFileCollection

    @TaskAction
    fun extract() {
        val libraries = librariesDir.get().asFile
        val natives = nativesDir.get().asFile.ensureDirectory()
        withEnvironment { it.extractNatives(libraries, natives) }
        deployOmlNative(natives)
    }

    /**
     * `oml-native` joins the vanilla natives in the same physical directory `java.library.path`
     * points at — the single-residency rule: the runtime's NativeManager never extracts anywhere
     * else, so dev runs and installer-laid-out trees look identical to it. Sources, in order: a
     * locally declared zig build tree ([omlNativeBuildDir] — the developer override), then the
     * published Maven artifact ([nativeJars] — the zero-config default).
     */
    private fun deployOmlNative(natives: File) {
        if (deployOmlNativeFromBuild(natives)) return
        if (deployOmlNativeFromMaven(natives)) return
        logger.warn(
            "[oml] oml-native not found — no local zig build (oml.omlNativeBuildDir) and no "
                + "published org.ohmyloader:oml-native artifact in the repositories; "
                + "OML's zstd codecs will use their vanilla fallbacks",
        )
    }

    private fun deployOmlNativeFromBuild(natives: File): Boolean {
        val buildDir = omlNativeBuildDir.orNull?.asFile
        val lib = buildDir?.let { OmlNativeLayout.candidatePaths(it).firstOrNull { f -> f.isFile } }
            ?: return false
        val target = File(natives, lib.name)
        if (target.isFile && sameContent(target, lib)) {
            logger.lifecycle("[oml] oml-native up to date in ${target.parentFile.absolutePath}")
            return true
        }
        lib.copyTo(target, overwrite = true)
        logger.lifecycle("[oml] oml-native deployed: ${target.absolutePath} (${lib.length() / 1024} KB)")
        return true
    }

    /**
     * Extracts the bare library from the classified jar's root (the launcher-distribution shape,
     * produced by the installer's `packageOmlNativeJars` and published as
     * `org.ohmyloader:oml-native:<v>:natives-<os>`).
     */
    private fun deployOmlNativeFromMaven(natives: File): Boolean {
        val jar = nativeJars.firstOrNull { it.isFile } ?: return false
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { Regex("""^oml-native\.(so|dll|dylib)$""").matches(it.name) }
                ?: return false.also {
                    logger.warn("[oml] $jar carries no oml-native library entry — publishing an empty classifier?")
                }
            val target = File(natives, entry.name)
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (target.isFile && sameContent(target, bytes)) {
                logger.lifecycle("[oml] oml-native (from ${jar.name}) up to date in ${target.parentFile.absolutePath}")
                return true
            }
            target.writeBytes(bytes)
            logger.lifecycle(
                "[oml] oml-native deployed from ${jar.name}: ${target.absolutePath} (${entry.size / 1024} KB)",
            )
            return true
        }
    }

    /**
     * Byte-wise comparison, not a length check: two builds of the same library are routinely the same
     * size while differing in content, and a size-only check leaves the stale one deployed forever —
     * the failure then looks like "the new zig build had no effect".
     */
    private fun sameContent(target: File, source: File): Boolean =
        target.length() == source.length() && target.readBytes().contentEquals(source.readBytes())

    private fun sameContent(target: File, bytes: ByteArray): Boolean =
        target.length().toInt() == bytes.size && target.readBytes().contentEquals(bytes)
}
