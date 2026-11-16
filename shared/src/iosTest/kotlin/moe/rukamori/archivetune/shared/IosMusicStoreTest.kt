@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosMusicStoreTest {
    @Test
    fun snapshotSurvivesReopeningAndReplacement() = runTest {
        val directory = NSTemporaryDirectory() + "archivetune-store-test-" + NSUUID().UUIDString
        try {
            val store = IosMusicStore(directory)
            assertNull(store.read())

            val original = """{"tracks":["שיר 🎵","日本語"],"version":1}"""
            store.write(original)
            assertEquals(original, IosMusicStore(directory).read())

            val replacement = """{"tracks":[],"version":2}"""
            store.write(replacement)
            assertEquals(replacement, IosMusicStore(directory).read())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }
}
