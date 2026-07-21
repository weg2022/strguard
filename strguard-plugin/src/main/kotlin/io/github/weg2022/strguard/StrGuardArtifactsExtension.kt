package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.util.Locale

open class StrGuardArtifactsExtension internal constructor(private val project: Project) {
    private val artifacts = linkedMapOf<String, StrGuardJvmArtifact>()

    fun jvm(targetName: String): StrGuardJvmArtifact = artifacts[targetName]
        ?: throw GradleException("StrGuard JVM artifact '$targetName' is not registered")

    internal fun register(
        targetName: String,
        protectedJar: Provider<RegularFile>,
        requiredShrinkerRules: Provider<RegularFile>,
    ) {
        check(artifacts.putIfAbsent(targetName, StrGuardJvmArtifact(project, targetName, protectedJar, requiredShrinkerRules)) == null) {
            "StrGuard JVM artifact '$targetName' is already registered"
        }
    }
}

open class StrGuardJvmArtifact internal constructor(
    private val project: Project,
    val targetName: String,
    val protectedJar: Provider<RegularFile>,
    val requiredShrinkerRules: Provider<RegularFile>,
) {
    private var verifierRegistered = false

    fun verifyShrunkJar(
        output: Provider<RegularFile>,
        shrinkerId: String,
    ): Provider<RegularFile> {
        if (verifierRegistered) {
            throw GradleException("A shrinker output is already registered for StrGuard JVM artifact '$targetName'")
        }
        require(shrinkerId.isNotBlank()) { "StrGuard shrinkerId must not be blank" }
        verifierRegistered = true
        val suffix =
            targetName.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
            }
        val task =
            project.tasks.register(
                "verifyStrGuard${suffix}ShrunkArtifact",
                VerifyStrGuardShrunkArtifactTask::class.java,
            )
        task.configure { verifier ->
            verifier.group = STRGUARD_TASK_GROUP
            verifier.description = "Verifies and finalizes the shrunk StrGuard $targetName JVM artifact."
            verifier.protectedJar.set(protectedJar)
            verifier.shrunkJar.set(output)
            verifier.shrinkerId.set(shrinkerId)
            verifier.verifiedJar.set(
                project.layout.buildDirectory.file("strguard/shrinker/$targetName/verified.jar"),
            )
        }
        return task.flatMap(VerifyStrGuardShrunkArtifactTask::verifiedJar)
    }
}
