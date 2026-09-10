/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modelruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidOnnxRuntimeTest {
    private val allProviders = setOf("QNN", "NNAPI", "XNNPACK", "CPU")

    @Test
    fun arm64_prefersQnnThenNnapiThenXnnpack() {
        assertEquals(
            listOf(AndroidOnnxProvider.QNN_HTP, AndroidOnnxProvider.NNAPI, AndroidOnnxProvider.XNNPACK),
            AndroidOnnxRuntime.permittedProviderOrder("arm64-v8a", 35, allProviders),
        )
    }

    @Test
    fun nonArm64_stillAllowsNnapiAndXnnpackButNeverQnn() {
        assertEquals(
            listOf(AndroidOnnxProvider.NNAPI, AndroidOnnxProvider.XNNPACK),
            AndroidOnnxRuntime.permittedProviderOrder("x86_64", 35, allProviders),
        )
    }

    @Test
    fun pre29_skipsNnapiBecauseCpuDisabledCannotBeEnforced() {
        assertEquals(
            listOf(AndroidOnnxProvider.QNN_HTP, AndroidOnnxProvider.XNNPACK),
            AndroidOnnxRuntime.permittedProviderOrder("arm64-v8a", 28, allProviders),
        )
    }

    @Test
    fun missingProvider_isNotConsideredAsFallback() {
        assertEquals(
            listOf(AndroidOnnxProvider.XNNPACK),
            AndroidOnnxRuntime.permittedProviderOrder("arm64-v8a", 35, setOf("CPU", "XNNPACK")),
        )
    }
}
