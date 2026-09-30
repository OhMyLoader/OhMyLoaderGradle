package org.ohmyloader.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Java 27 launch contract.
 *
 * Worth a test of its own because the arguments cannot be discovered by reading the code that uses them:
 * they are required by the JDK and by JOML's native loading, they are identical on both sides, and the
 * failure they prevent ranges from a warning today to a refusal in a future release. Nothing else in the
 * build would notice one going missing.
 */
class OmlJvmContractTest {

    @Test
    fun `the contract carries native access, the four opens and the encoding flags`() {
        val args = OmlJvmContract.jvmArgs(null)

        assertEquals(args, OmlJvmContract.BASE_JVM_ARGS)
        assertTrue(args.contains("--enable-native-access=ALL-UNNAMED"))

        // Deep reflection into the JDK's own classes; there is no narrower way to grant it.
        listOf("java.lang", "java.lang.reflect", "java.util", "java.nio").forEach { pkg ->
            assertTrue(
                args.contains("--add-opens=java.base/$pkg=ALL-UNNAMED"),
                "missing --add-opens for $pkg",
            )
        }

        // A zh-CN Windows console defaults to GBK, which turns the runtime's Chinese log lines into
        // mojibake. The runtime writes UTF-8 by design.
        assertTrue(args.contains("-Dsun.stdout.encoding=UTF-8"))
        assertTrue(args.contains("-Dsun.stderr.encoding=UTF-8"))

        // An out-of-memory game is dead; exit so a supervisor sees it instead of limping on.
        assertTrue(args.contains("-XX:+ExitOnOutOfMemoryError"))
        // Equal backing arrays shared under G1; Minecraft duplicates strings in bulk.
        assertTrue(args.contains("-XX:+UseStringDeduplication"))
    }

    @Test
    fun `the launcher main class is the one both sides start`() {
        assertEquals("org.ohmyloader.launcher.OMLBootstrap", OmlJvmContract.LAUNCHER_MAIN_CLASS)
    }

    @Test
    fun `a heap value is appended last, so it can override nothing`() {
        val withHeap = OmlJvmContract.jvmArgs("-Xmx4G")
        assertEquals(OmlJvmContract.BASE_JVM_ARGS.size + 1, withHeap.size)
        assertEquals("-Xmx4G", withHeap.last())
        assertEquals(OmlJvmContract.BASE_JVM_ARGS, withHeap.dropLast(1))
    }

    @Test
    fun `a blank heap is not appended`() {
        // An empty string is what the extension's convention produces, and passing it through would put
        // a bare "" on the JVM's command line.
        listOf(null, "", "   ").forEach { heap ->
            assertEquals(
                OmlJvmContract.BASE_JVM_ARGS,
                OmlJvmContract.jvmArgs(heap),
                "heap ${heap?.let { "'$it'" } ?: "null"} must not produce an argument",
            )
        }
    }

    @Test
    fun `a heap value is trimmed`() {
        assertTrue(OmlJvmContract.jvmArgs("  -Xmx2G  ").contains("-Xmx2G"))
    }

    @Test
    fun `the base list is stable, so a missing argument is a test failure rather than a diff`() {
        // Deliberately asserts the exact contents: this list is a contract with the JDK, and a change to
        // it should be a visible decision rather than a quiet edit.
        assertEquals(
            listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:+UseStringDeduplication",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
            ),
            OmlJvmContract.BASE_JVM_ARGS,
        )
    }
}
