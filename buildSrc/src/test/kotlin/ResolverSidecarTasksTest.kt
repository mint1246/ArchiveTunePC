/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResolverSidecarTasksTest {
    @get:Rule val temporary = TemporaryFolder()
    private val member = "bun-linux-x64/bun"
    private val payload = "fixture executable".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun archive(names: List<String>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith('/')) zip.write(payload)
                zip.closeEntry()
            }
        }
    }

    private fun extraction(zip: File): ExtractVerifiedArchiveEntryTask {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        return project.tasks.create("extract", ExtractVerifiedArchiveEntryTask::class.java).apply {
            sourceArchive.set(zip)
            archiveMember.set(member)
            expectedSha256.set(hash)
            outputFile.set(project.layout.projectDirectory.file("output/bun"))
        }
    }

    @Test fun `extracts only the verified executable and preserves a previous output on failure`() {
        val task = extraction(archive(listOf("bun-linux-x64/", member)))
        task.extract()
        assertArrayEquals(payload, task.outputFile.get().asFile.readBytes())
        task.expectedSha256.set("0".repeat(64))
        assertThrows(GradleException::class.java) { task.extract() }
        assertArrayEquals(payload, task.outputFile.get().asFile.readBytes())
        assertFalse(File(task.outputFile.get().asFile.path + ".part").exists())
    }

    @Test fun `rejects unsafe and unexpected ZIP entries without publishing files`() {
        listOf("../outside", "/absolute", "C:/drive", "bad\\path", "bad\u0001name", "unrequested").forEach { bad ->
            val task = extraction(archive(listOf(member, bad)))
            assertThrows("Entry: $bad", GradleException::class.java) { task.extract() }
            assertFalse(task.outputFile.get().asFile.exists())
        }
        val directoryMember = extraction(archive(listOf("bun-linux-x64/", "$member/")))
        assertThrows(GradleException::class.java) { directoryMember.extract() }
    }

    @Test fun `rejects excessive entry count before materializing output`() {
        val task = extraction(archive((0..1024).map { "directory-$it/" }))
        val failure = assertThrows(GradleException::class.java) { task.extract() }
        assertTrue(failure.message.orEmpty().contains("1,024") || failure.message.orEmpty().contains("1024"))
        assertFalse(task.outputFile.get().asFile.exists())
    }

    @Test fun `rejects duplicate members and oversized declared members`() {
        // ZipOutputStream rejects duplicates itself. Rewrite the second equal-length
        // name in both ZIP headers to construct the malformed upstream fixture.
        val alias = member.dropLast(1) + "x"
        val duplicate = archive(listOf(member, alias))
        val bytes = duplicate.readBytes()
        val aliasBytes = alias.toByteArray()
        for (offset in 0..bytes.size - aliasBytes.size) {
            if (aliasBytes.indices.all { bytes[offset + it] == aliasBytes[it] }) {
                bytes[offset + aliasBytes.lastIndex] = 'n'.code.toByte()
            }
        }
        duplicate.writeBytes(bytes)
        val duplicateTask = extraction(duplicate)
        assertThrows(GradleException::class.java) { duplicateTask.extract() }
        assertFalse(duplicateTask.outputFile.get().asFile.exists())

        val oversized = archive(listOf(member))
        val oversizedBytes = oversized.readBytes()
        val centralOffset = oversizedBytes.indices.first { offset ->
            offset + 4 <= oversizedBytes.size &&
                oversizedBytes.sliceArray(offset until offset + 4).contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        }
        java.nio.ByteBuffer.wrap(oversizedBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(centralOffset + 24, 256 * 1024 * 1024 + 1)
        oversized.writeBytes(oversizedBytes)
        val oversizedTask = extraction(oversized)
        val failure = assertThrows(GradleException::class.java) { oversizedTask.extract() }
        assertTrue(failure.message.orEmpty().contains("too large"))
        assertFalse(oversizedTask.outputFile.get().asFile.exists())
    }

    @Test fun `verifies every descriptive manifest field as well as the binary digests`() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val source = project.projectDir.resolve("source").apply { mkdirs() }
        val binary = source.resolve("binary").apply { writeBytes(payload) }
        val notices = source.resolve("notices").apply { writeText("Fixture notices") }
        val licenses = source.resolve("licenses").apply { mkdirs() }
        listOf("yt-dlp-UNLICENSE.txt", "bun-LICENSE.md", "bun-LGPL-2.0.txt", "bun-LGPL-2.1.txt",
            "bun-WEBKIT-NOTICE.md", "yt-dlp-THIRD_PARTY_LICENSES.txt", "yt-dlp-ejs-UNLICENSE.txt",
            "pyinstaller-6.22.0-COPYING.txt").forEach { licenses.resolve(it).writeText("Fixture license") }
        val platform = ResolverSidecars.LINUX_X86_64
        val ytDlp = ResolverSidecars.assetFor(platform, ResolverSidecarKind.YT_DLP)
        val bun = ResolverSidecars.assetFor(platform, ResolverSidecarKind.BUN)
        val staged = project.layout.projectDirectory.dir("staged")
        project.tasks.create("stage", StageResolverSidecarsTask::class.java).apply {
            platformId.set(platform)
            ytDlpBinary.set(binary); bunBinary.set(binary)
            ytDlpVersion.set(ytDlp.version); bunVersion.set(bun.version)
            ytDlpUrl.set(ytDlp.url); bunUrl.set(bun.url)
            ytDlpSha256.set(hash); bunSha256.set(hash); bunBinarySha256.set(hash)
            bundledEjsVersion.set(ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION)
            thirdPartyNotices.set(notices); licensesDirectory.set(licenses)
            destinationDirectory.set(staged)
            stage()
        }
        val verifier = project.tasks.create("verify", VerifyResolverSidecarsTask::class.java).apply {
            platformId.set(platform); sidecarDirectory.set(staged)
            ytDlpSha256.set(hash); bunSha256.set(hash); bunBinarySha256.set(hash)
        }
        verifier.verify()
        val manifest = staged.file("manifest.properties").asFile
        val original = manifest.readText()
        listOf("yt-dlp.version", "yt-dlp.file", "yt-dlp.url", "yt-dlp.bundled-ejs.version",
            "bun.version", "bun.file", "bun.url").forEach { key ->
            manifest.writeText(original.replace(Regex("(?m)^${Regex.escape(key)}=.*$"), "$key=invalid"))
            assertThrows("Field: $key", GradleException::class.java) { verifier.verify() }
        }
        manifest.writeText(original)
        staged.file("qjs").asFile.writeText("stale runtime")
        assertThrows(GradleException::class.java) { verifier.verify() }
    }
}
