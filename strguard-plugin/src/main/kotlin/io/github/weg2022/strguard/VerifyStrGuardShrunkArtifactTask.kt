package io.github.weg2022.strguard

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyStrGuardShrunkArtifactTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protectedJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shrunkJar: RegularFileProperty

    @get:Input
    abstract val shrinkerId: Property<String>

    @get:OutputFile
    abstract val verifiedJar: RegularFileProperty

    @TaskAction
    fun verify() {
        StrGuardShrunkArtifactFinalizer.finalize(
            protectedJar = protectedJar.get().asFile.toPath(),
            shrunkJar = shrunkJar.get().asFile.toPath(),
            verifiedJar = verifiedJar.get().asFile.toPath(),
            shrinkerId = shrinkerId.get(),
        )
    }
}
