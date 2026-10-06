package org.ohmyloader.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `oml { ... }` — the whole declaration surface of the plugin.
 *
 * Every field is a Gradle managed property. Conventions are installed inside the consumer's
 * `plugins { }` block, *before* the rest of their script runs, so an eager read would only ever see
 * the default; and the configuration cache refuses to serialize a task action that reaches back into
 * the `Project` — a managed `DirectoryProperty` serializes cleanly, a captured `var runDir: File`
 * would not. Paths are absolute throughout: the run tasks work in a different directory than the
 * project, and the runtime takes them as `-D` properties where a relative path would resolve against
 * the wrong base and fail silently — a wrong mods directory simply loads no mods.
 */
abstract class OmlExtension {

    /**
     * The Minecraft version this project targets, e.g. `26.3`.
     *
     * Intentionally **without** a convention: a project that forgot to declare it would otherwise
     * fetch and launch a version nobody asked for, surfacing only as a confusing injection failure
     * much later. The fetch tasks fail with a message naming this property instead.
     *
     * The literal `snapshot` is an alias resolved to the manifest's `latest.snapshot` at task
     * execution, so tracking snapshots never means editing this value; [adapterArtifact]'s
     * convention derives `oml-adapter-snapshot` for it. Run state is keyed by the literal, so it is
     * shared between snapshots — fetches re-verify by SHA-1 and replace changed files, but libraries
     * a superseded snapshot dropped are never swept.
     */
    abstract val minecraftVersion: Property<String>

    /**
     * Version of the OML artifacts (`oml-api`, `oml-core`, `oml-launcher`, `oml-devtools`).
     *
     * Defaults to the `oml_version` project property when the consumer's build declares one, and to
     * [DEFAULT_API_VERSION] otherwise. One version for the whole layer on purpose: the modules share
     * internal contracts, so mixing `oml-core` 0.2.0 with `oml-launcher` 0.1.0 produces a
     * `NoSuchMethodError` far from its cause.
     */
    abstract val apiVersion: Property<String>

    /**
     * The artifactId of the per-version adapter on the launch classpath, e.g. `oml-adapter-26_3`.
     *
     * Defaults to `oml-adapter-` + [minecraftVersion] with dots replaced by underscores — a
     * *convention*, so an unusual version id can override it. Snapshot versions are the case that
     * must: the snapshot adapter is `oml-adapter-snapshot` for every snapshot id, so a project
     * tracking snapshots sets it explicitly. Without an adapter there is no `IAdapter`
     * implementation and the launcher dies with "no IAdapter implementation found" — the loader
     * reaches the game through this per-version integration layer, so it *is* the layer.
     */
    abstract val adapterArtifact: Property<String>

    /** e.g. `-Xmx4G`. Empty (the default) leaves the JVM's own default in place. */
    abstract val heap: Property<String>

    /**
     * Extra arguments appended to the game's own argument list, after the ones OML supplies.
     *
     * This is how a version-specific requirement is expressed from the consumer side — a game line that
     * needs `--userProperties {}`, say — without the plugin hard-coding a per-version table it cannot
     * keep current.
     */
    abstract val extraGameArgs: ListProperty<String>

    /**
     * Extra **JVM** arguments appended after [org.ohmyloader.gradle.OmlJvmContract.BASE_JVM_ARGS],
     * for both runClient and runServer — debugging flags (`-agentlib:jdwp`), renderer workarounds
     * (`-Dorg.lwjgl.opengl.libname=...`), GC experiments. Game (program) arguments go to
     * [extraGameArgs] instead.
     */
    abstract val extraJvmArgs: ListProperty<String>

    // ---------------------------------------------------------------------------------------------
    // Where things live
    // ---------------------------------------------------------------------------------------------

    /**
     * Root of the generated state. Every other directory below defaults to a child of this one; the
     * version-specific artifacts — [librariesDir], [nativesDir], [clientJar] — sit one level deeper,
     * under a `<version>/` directory, so two game versions can never see each other's jars, while
     * [assetsDir] and [modsDir] sit directly under it (assets are content-addressed, and mods are
     * meant to be shared).
     *
     * All paths here derive from this single property, so relocating `run/` relocates the whole set
     * consistently — unlike the adapter convention, which writes into fixed module-relative paths.
     */
    abstract val runDir: DirectoryProperty

    /** Game libraries, laid out at their Maven paths. */
    abstract val librariesDir: DirectoryProperty

    /** Unpacked natives of the running platform; `java.library.path` for a launch points here. */
    abstract val nativesDir: DirectoryProperty

    /**
     * The `oml-native` zig project: the directory holding `build.zig`, `build.zig.zon` and `src/`.
     *
     * Defaults to `oml-native/` beside the *root* project. The zig project lives in the loader
     * repository, not this one — so the default only resolves in a checkout that also contains it
     * (a development workspace with sibling clones); every other project resolves the published
     * Maven artifact instead. [omlNativeBuildDir] follows it. The rebuild task is registered only
     * when this directory really carries a `build.zig`, and since that check happens at
     * configuration time while `oml { }` runs *after* `plugins { }`, the registration is deferred
     * until the project has been evaluated (see `OmlPlugin.registerFetchTasks`).
     */
    abstract val nativeProjectDir: DirectoryProperty

    /**
     * Root of the zig build tree for our own `oml-native` library (the directory holding
     * `<plat>/<arch>/release/oml-native.<ext>`). `extractNatives` deploys the current platform's
     * library into [nativesDir] from here, so it is derived from [nativeProjectDir]; the convention
     * only resolves when the zig project sits beside the root project (a development checkout). Set
     * it (or leave the library away) and the deploy degrades to a logged skip — every runtime
     * consumer of the library also supports the vanilla fallbacks.
     */
    abstract val omlNativeBuildDir: DirectoryProperty

    /** The vanilla client jar. Fetched by `fetchClientJar`. */
    abstract val clientJar: RegularFileProperty

    /** Where mods are loaded from. Absolute in the JVM contract, because it is not the working directory. */
    abstract val modsDir: DirectoryProperty

    /** Game assets, filled in by `fetchAssets` and handed to the game as `--assetsDir`. */
    abstract val assetsDir: DirectoryProperty

    /**
     * The asset index id, e.g. `34`.
     *
     * The mapping from version to index id is not a rule — it is a fact of the version's launcher
     * metadata, and the version.json embedded in the client jar does not carry it. A guessed id makes
     * the client start and then silently miss every resource that lives only in the asset index (in
     * 26.3: all sound definitions — textures and lang ship in the jar, so the game looks fine until a
     * sound plays). Left unset, `runClient` uses the id the `fetchAssets` task resolved from the
     * version metadata and recorded in the assets directory; set this only to override that value.
     */
    abstract val assetIndex: Property<String>

    /** Value of the runtime's `oml.diagnostics` switch, e.g. `inject`. Empty means off. */
    abstract val diagnostics: Property<String>

    companion object {
        /**
         * Fallback for [apiVersion] when the consumer's build declares no `oml_version` property: the
         * version this plugin was itself built as, stamped into `oml-gradle.properties` at build time
         * from `project.version`. Stamping is the point — a literal here drifts from the loader the
         * plugin actually ships against, and the mismatch only surfaces later as an unresolvable
         * coordinate. A consumer that wants to pin a different version sets the property or the
         * extension field.
         */
        val DEFAULT_API_VERSION: String = stampedVersion()

        private fun stampedVersion(): String =
            OmlExtension::class.java.getResourceAsStream("/oml-gradle.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }
                    .getProperty("version")?.takeIf { it.isNotBlank() }
            } ?: "unknown"
    }
}
