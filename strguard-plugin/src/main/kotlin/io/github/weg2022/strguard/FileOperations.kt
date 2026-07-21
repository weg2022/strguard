package io.github.weg2022.strguard

import org.gradle.api.GradleException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

internal fun resetDirectory(path: Path) {
    if (Files.exists(path) && !path.toFile().deleteRecursively()) {
        throw GradleException("StrGuard cannot clean directory $path")
    }
    Files.createDirectories(path)
}

internal fun extractZip(input: InputStream, destination: Path) {
    val root = destination.toAbsolutePath().normalize()
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val output = root.resolve(entry.name).normalize()
            if (!output.startsWith(root)) {
                throw GradleException("StrGuard rejected archive entry outside its workspace: ${entry.name}")
            }
            if (entry.isDirectory) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use(zip::copyTo)
            }
            zip.closeEntry()
        }
    }
}
