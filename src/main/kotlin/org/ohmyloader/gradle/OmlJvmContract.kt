package org.ohmyloader.gradle

/**
 * The JVM contract a launched OML game requires, in one place. `oml-launcher`'s jar manifest carries the
 * same flags so a bare `java -jar oml-launcher.jar` (how an installer-produced server starts) is complete;
 * that copy cannot be imported here, so the two must agree by hand — a flag added to one only shows up as
 * a launch warning, never a build error; the manifest cannot carry `-XX` arguments, so the
 * installer-written start scripts are the copy that has the two `-XX` flags there. Per flag:
 * `--enable-native-access` loads the game's LWJGL natives through OML's loader path (Java 27 warns, a
 * future release refuses outright); the four `--add-opens` are deep-reflection opens for OML's injection
 * into JDK classes; the UTF-8 encoding pair guards against the GBK default of a zh-CN Windows console;
 * `ExitOnOutOfMemoryError` makes an OOM exit so restart wrappers and CI see the failure; string
 * deduplication is cheap G1 memory; the path-carrying `-Djava.library.path` / `-Doml.*` are excluded.
 */
internal object OmlJvmContract {

    /** The arguments that do not depend on any resolved path. Order is stable so tests can assert it. */
    val BASE_JVM_ARGS: List<String> = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+UseStringDeduplication",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )

    /** The main class every launcher-shaped start goes through, whatever the side. */
    const val LAUNCHER_MAIN_CLASS: String = "org.ohmyloader.launcher.OMLBootstrap"

    /**
     * [BASE_JVM_ARGS] plus a heap flag when one is configured.
     *
     * A blank or malformed heap value is passed through unchanged rather than validated here: the JVM
     * itself reports `Unrecognized option: -Xmx` with the offending text, which is a better message than
     * anything this object could invent.
     */
    fun jvmArgs(heap: String?): List<String> {
        val trimmed = heap?.trim().orEmpty()
        return if (trimmed.isEmpty()) BASE_JVM_ARGS else BASE_JVM_ARGS + trimmed
    }
}
