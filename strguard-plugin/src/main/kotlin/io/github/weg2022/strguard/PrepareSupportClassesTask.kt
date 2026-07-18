package io.github.weg2022.strguard

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PrepareSupportClassesTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDirectory.get().asFile.toPath()
        destination.toFile().deleteRecursively()
        Files.createDirectories(destination)
        SupportClassFiles.writeAnnotations(destination)
    }
}
