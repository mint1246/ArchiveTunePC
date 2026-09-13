/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PatchCloudflaredForAndroidTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun patch() {
        val bytes = inputFile.get().asFile.readBytes()
        val linuxResolverPath = "/etc/resolv.conf".toByteArray(Charsets.US_ASCII)
        val androidResolverPath = "/proc/self/cwd/r".toByteArray(Charsets.US_ASCII)
        check(linuxResolverPath.size == androidResolverPath.size)

        var replacements = 0
        var index = 0
        while (index <= bytes.size - linuxResolverPath.size) {
            var matches = true
            for (offset in linuxResolverPath.indices) {
                if (bytes[index + offset] != linuxResolverPath[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                androidResolverPath.copyInto(bytes, destinationOffset = index)
                replacements++
                index += linuxResolverPath.size
            } else {
                index++
            }
        }

        if (replacements != 1) {
            throw GradleException("Expected one cloudflared resolver path to patch, found $replacements")
        }

        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeBytes(bytes)
    }
}
