package org.ohmyloader.gradle

import java.io.File

/**
 * Byte-wise comparison, not a length check: two builds of the same library are routinely the same
 * size while differing in content, and a size-only check leaves the stale one deployed forever —
 * the failure then looks like "the new zig build had no effect".
 */
internal fun sameBytes(target: File, source: File): Boolean =
    target.length() == source.length() && target.readBytes().contentEquals(source.readBytes())

internal fun sameBytes(target: File, bytes: ByteArray): Boolean =
    target.length().toInt() == bytes.size && target.readBytes().contentEquals(bytes)
