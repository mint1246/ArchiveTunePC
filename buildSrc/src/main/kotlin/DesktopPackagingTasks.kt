/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.DataOutputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO

@DisableCachingByDefault(because = "The generated ICO is tiny and inexpensive to recreate.")
abstract class GenerateWindowsIcoTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcePng: RegularFileProperty

    @get:OutputFile
    abstract val outputIco: RegularFileProperty

    @TaskAction
    fun generate() {
        val source = sourcePng.get().asFile
        val image = ImageIO.read(source)
            ?: throw GradleException("Unable to decode ArchiveTune launcher icon: $source")
        if (image.width !in 1..256 || image.height !in 1..256) {
            throw GradleException(
                "Windows launcher icon must be at most 256x256, but was ${image.width}x${image.height}",
            )
        }

        val pngBytes = source.readBytes()
        val output = outputIco.get().asFile
        output.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(output)).use { stream ->
            fun writeLittleEndianShort(value: Int) {
                stream.writeByte(value and 0xFF)
                stream.writeByte((value ushr 8) and 0xFF)
            }

            fun writeLittleEndianInt(value: Int) {
                stream.writeByte(value and 0xFF)
                stream.writeByte((value ushr 8) and 0xFF)
                stream.writeByte((value ushr 16) and 0xFF)
                stream.writeByte((value ushr 24) and 0xFF)
            }

            writeLittleEndianShort(0)
            writeLittleEndianShort(1)
            writeLittleEndianShort(1)
            stream.writeByte(if (image.width == 256) 0 else image.width)
            stream.writeByte(if (image.height == 256) 0 else image.height)
            stream.writeByte(0)
            stream.writeByte(0)
            writeLittleEndianShort(1)
            writeLittleEndianShort(32)
            writeLittleEndianInt(pngBytes.size)
            writeLittleEndianInt(22)
            stream.write(pngBytes)
        }
    }
}

@DisableCachingByDefault(because = "This verification launches the packaged native executable.")
abstract class VerifyBundledExecutableTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectory: DirectoryProperty

    @get:Input
    abstract val executablePathSuffix: Property<String>

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val expectedOutput: Property<String>

    @TaskAction
    fun verify() {
        val directory = appDirectory.get().asFile
        val suffix = executablePathSuffix.get().replace('\\', '/')
        val runtime = directory
            .walkTopDown()
            .filter { it.isFile }
            // Keep this task independent of Kotlin DSL-only File extensions: buildSrc is compiled
            // as a regular Kotlin/JVM source set and must work with Gradle's public API alone.
            .singleOrNull { it.path.replace('\\', '/').endsWith(suffix) }
            ?: throw GradleException("Bundled executable *$suffix was not found below $directory")
        val process = ProcessBuilder(listOf(runtime.absolutePath) + arguments.get())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || !output.contains(expectedOutput.get(), ignoreCase = true)) {
            throw GradleException(
                "Bundled executable verification failed (exit $exitCode): ${output.take(2_000)}",
            )
        }
    }
}

@DisableCachingByDefault(because = "This verification inspects the packaged native VLC runtime.")
abstract class VerifyBundledVlcRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val roots = appDirectory.get().asFile.walkTopDown()
            .filter { it.isDirectory && it.path.replace('\\', '/').endsWith("/resources/native/windows-x64/vlc") }
            .toList()
        val root = roots.singleOrNull()
            ?: throw GradleException("Packaged libVLC runtime directory was not found below ${appDirectory.get().asFile}")
        val libVlc = root.resolve("libvlc.dll")
        val libVlcCore = root.resolve("libvlccore.dll")
        val plugins = root.resolve("plugins")
        if (!libVlc.isFile || !libVlcCore.isFile || !plugins.isDirectory) {
            throw GradleException("Incomplete libVLC runtime at ${root.absolutePath}; expected DLLs and plugins/")
        }
    }
}
