package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.nio.file.Files

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
        Files.writeString(
            destination.resolve(STRGUARD_SHRINKER_RULES_FILE_NAME),
            StrGuardShrinkerRules.text,
        )
    }
}

internal const val STRGUARD_SHRINKER_RULES_FILE_NAME = "strguard-rules.pro"
