package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader

@CacheableTask
abstract class TransformClassesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputClassDirectories: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val nativeInputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    @get:Input
    abstract val stringGuardEnabled: Property<Boolean>

    @get:Internal
    abstract val releaseSeedHex: Property<String>

    @get:Input
    abstract val releaseSeedFingerprint: Property<String>

    @get:Input
    abstract val moduleIdentity: Property<String>

    @get:Input
    abstract val java9StringConcatEnabled: Property<Boolean>

    @get:Input
    abstract val consoleOutput: Property<Boolean>

    @get:Input
    abstract val removeMetadata: Property<Boolean>

    @get:Input
    abstract val stringGuardPackages: ListProperty<String>

    @get:Input
    abstract val keepStringPackages: ListProperty<String>

    @get:Input
    abstract val removeMetadataPackages: ListProperty<String>

    @get:Input
    abstract val keepMetadataPackages: ListProperty<String>

    @TaskAction
    fun transform() {
        val settings =
            TransformSettings(
                enabled = stringGuardEnabled.get(),
                java9StringConcatEnabled = java9StringConcatEnabled.get(),
                removeMetadata = removeMetadata.get(),
                stringGuardPackages = stringGuardPackages.get(),
                keepStringPackages = keepStringPackages.get(),
                removeMetadataPackages = removeMetadataPackages.get(),
                keepMetadataPackages = keepMetadataPackages.get(),
            )
        val sources = collectInputFiles()
        val inputDigest = digestInputs(sources)
        val seed = validatedSeed(settings.enabled)
        val vaultBuilder = SecureVaultBuilder(seed, moduleIdentity.get(), inputDigest)

        val destination = outputDirectory.get().asFile.toPath()
        val nativeInputs = nativeInputDirectory.get().asFile.toPath()
        val reports = reportDirectory.get().asFile.toPath()
        resetDirectory(destination)
        resetDirectory(nativeInputs)
        resetDirectory(reports)

        val metadataMappings = linkedSetOf<String>()
        sources.forEach { source ->
            val target = destination.resolve(source.relativePath)
            Files.createDirectories(target.parent)
            if (source.source.fileName.toString().endsWith(".class")) {
                val originalBytes = Files.readAllBytes(source.source)
                val className = ClassReader(originalBytes).className
                if (settings.shouldTransformClass(className)) {
                    val result = ClassTransformer.transform(originalBytes, settings, vaultBuilder)
                    metadataMappings.addAll(result.metadataMappings)
                    Files.write(target, result.bytes)
                } else {
                    Files.copy(source.source, target)
                }
            } else {
                Files.copy(source.source, target)
            }
        }

        if (settings.enabled) {
            SupportClassFiles.writeRuntimeAndAnnotations(destination, vaultBuilder.bridge)
            vaultBuilder.writeNativeInputs(nativeInputs)
        }
        Files.writeString(
            reports.resolve("summary.txt"),
            "protectedStrings=${vaultBuilder.protectedStringCount}\n" +
                "removedMetadata=${metadataMappings.size}\n",
            StandardCharsets.UTF_8,
        )

        if (consoleOutput.get()) {
            logger.lifecycle(
                "StrGuard 2 protected ${vaultBuilder.protectedStringCount} call sites and removed " +
                    "${metadataMappings.size} metadata annotations",
            )
        }
    }

    private fun validatedSeed(enabled: Boolean): String {
        if (!enabled) {
            return DISABLED_SEED
        }
        val seed = releaseSeedHex.orNull
            ?: throw GradleException(
                "StrGuard 2 requires strGuard.releaseSeedHex or STRGUARD_RELEASE_SEED_HEX",
            )
        val seedBytes =
            try {
                CryptoPrimitives.parseHex256(seed)
            } catch (failure: IllegalArgumentException) {
                throw GradleException(failure.message ?: "Invalid StrGuard release seed", failure)
            }
        val actualFingerprint = CryptoPrimitives.hex(CryptoPrimitives.sha256(seedBytes))
        if (actualFingerprint != releaseSeedFingerprint.get()) {
            throw GradleException("StrGuard release seed fingerprint does not match the configured seed")
        }
        seedBytes.fill(0)
        return seed
    }

    private fun collectInputFiles(): List<InputFile> {
        val roots =
            inputClassDirectories.files
                .map { it.toPath().toAbsolutePath().normalize() }
                .filter(Files::isDirectory)
                .sortedBy(Path::toString)
        val files = mutableListOf<InputFile>()
        val relativePaths = mutableSetOf<String>()
        roots.forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).forEach { source ->
                    val relative = root.relativize(source).normalize()
                    val normalizedRelative = relative.toString().replace('\\', '/')
                    if (!relativePaths.add(normalizedRelative)) {
                        throw GradleException("StrGuard found duplicate class output $normalizedRelative")
                    }
                    files += InputFile(source, relative, normalizedRelative)
                }
            }
        }
        return files.sortedBy(InputFile::normalizedRelativePath)
    }

    private fun digestInputs(sources: List<InputFile>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        sources.forEach { source ->
            val pathBytes = source.normalizedRelativePath.toByteArray(StandardCharsets.UTF_8)
            digest.update(CryptoPrimitives.intLe(pathBytes.size))
            digest.update(pathBytes)
            val bytes = Files.readAllBytes(source.source)
            digest.update(CryptoPrimitives.intLe(bytes.size))
            digest.update(bytes)
        }
        return digest.digest()
    }

    private fun resetDirectory(directory: Path) {
        directory.toFile().deleteRecursively()
        Files.createDirectories(directory)
    }
}

private data class InputFile(
    val source: Path,
    val relativePath: Path,
    val normalizedRelativePath: String,
)

private const val DISABLED_SEED =
    "0000000000000000000000000000000000000000000000000000000000000000"
