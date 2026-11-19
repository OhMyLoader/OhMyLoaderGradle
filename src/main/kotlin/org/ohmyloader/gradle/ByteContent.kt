package org.ohmyloader.gradle

import java.io.File

/**
 * Byte-wise comparison, not a length check: two builds of the same library are routinely the same
 * size while differing in content, and a size-only check leaves the stale one deployed forever —
 * the failure then looks like "the new native build had no effect".
 */
internal fun sameBytes(target: File, source: File): Boolean =
    target.length() == source.length() && target.readBytes().contentEquals(source.readBytes())

internal fun sameBytes(target: File, bytes: ByteArray): Boolean =
    target.length().toInt() == bytes.size && target.readBytes().contentEquals(bytes)

/**
 * Moves [tmp] into [target] atomically, replacing any existing file. Never write a native
 * library in place (`copyTo(overwrite)` / `writeBytes` truncate the target): the multiplayer
 * dev run boots the server and client JVMs against one shared `natives/` directory, and a JVM
 * that already `dlopen`-ed a library dies with SIGBUS BUS_ADRERR when another process truncates
 * its mapped text segment mid-run. A rename keeps the mapped inode of a live reader intact.
 */
internal fun moveIntoPlace(tmp: File, target: File) {
    try {
        java.nio.file.Files.move(
            tmp.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
