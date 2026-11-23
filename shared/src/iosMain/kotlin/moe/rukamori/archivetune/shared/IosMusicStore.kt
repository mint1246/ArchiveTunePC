@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** App sandbox storage; Foundation's atomic write replaces the complete snapshot. */
class IosMusicStore internal constructor(private val storageDirectory: String?) : MusicStore {
    constructor() : this(null)
    private fun path(): String {
        val manager = NSFileManager.defaultManager
        val directory = storageDirectory ?: (requireNotNull(manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull() as? platform.Foundation.NSURL).path!! + "/ArchiveTune")
        check(manager.createDirectoryAtPath(directory, true, null, null)) { "Cannot create music storage." }
        return "$directory/library.json"
    }

    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        val file = path()
        if (!NSFileManager.defaultManager.fileExistsAtPath(file)) null
        else checkNotNull(NSString.stringWithContentsOfFile(file, NSUTF8StringEncoding, null)) {
            "Cannot read music storage."
        }
    }

    override suspend fun write(json: String) = withContext(Dispatchers.Default) {
        check(NSString.create(string = json).writeToFile(path(), true, NSUTF8StringEncoding, null)) {
            "Cannot save music storage."
        }
    }
}
