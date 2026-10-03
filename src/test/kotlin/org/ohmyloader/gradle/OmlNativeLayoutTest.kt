package org.ohmyloader.gradle

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The platform bridge between the build tree's spelling (`macosx`) and the published classifier's
 * (`osx`). Value mapping only — the point of pinning it is that a wrong token resolves a classifier
 * that does not exist, and the failure then surfaces much later as an unresolved/pinned dependency.
 */
class OmlNativeLayoutTest {

    @Test
    fun `the classifier os follows the JVM's platform`() {
        assertEquals("osx", OmlNativeLayout.nativeClassifierOs("Mac OS X"))
        assertEquals("windows", OmlNativeLayout.nativeClassifierOs("Windows 11"))
        assertEquals("linux", OmlNativeLayout.nativeClassifierOs("Linux"))
    }

    @Test
    fun `an unknown os fails with a message that names it`() {
        // Not a silent default: any default would resolve a library this JVM cannot load.
        val error = assertFailsWith<GradleException> { OmlNativeLayout.nativeClassifierOs("Plan 9") }
        assertTrue(error.message!!.contains("Plan 9"), error.message)
    }

    @Test
    fun `the classifier arch suffix distinguishes arm64 from x86_64`() {
        assertEquals("-arm64", OmlNativeLayout.nativeClassifierArchSuffix("aarch64"))
        assertEquals("-arm64", OmlNativeLayout.nativeClassifierArchSuffix("arm64"))
        assertEquals("", OmlNativeLayout.nativeClassifierArchSuffix("amd64"))
        assertEquals("", OmlNativeLayout.nativeClassifierArchSuffix("x86_64"))
    }
}
