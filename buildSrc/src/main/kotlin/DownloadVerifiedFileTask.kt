/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.security.MessageDigest

abstract class DownloadVerifiedFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val destination = outputFile.get().asFile
        val expected = expectedSha256.get().lowercase()
        destination.parentFile.mkdirs()

        if (destination.isFile && sha256(destination) == expected) return

        val temporary = destination.resolveSibling("${destination.name}.part")
        temporary.delete()
        try {
            val connection = URI(sourceUrl.get()).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "ArchiveTune-build")
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            val actual = sha256(temporary)
            if (actual != expected) {
                throw GradleException("Downloaded file checksum mismatch: expected $expected, got $actual")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
