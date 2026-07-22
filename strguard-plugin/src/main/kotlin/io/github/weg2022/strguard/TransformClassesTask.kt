package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
    abstract val targetTriple: Property<String>

    @get:Input
    abstract val java9StringConcatEnabled: Property<Boolean>

    @get:Input
    abstract val strictStringCoverage: Property<Boolean>

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
                strictStringCoverage = strictStringCoverage.get(),
                removeMetadata = removeMetadata.get(),
                stringGuardPackages = stringGuardPackages.get(),
                keepStringPackages = keepStringPackages.get(),
                removeMetadataPackages = removeMetadataPackages.get(),
                keepMetadataPackages = keepMetadataPackages.get(),
            )
        val sources = collectInputFiles()
        val destinationOutput = outputDirectory.get().asFile.toPath()
        val nativeInputsOutput = nativeInputDirectory.get().asFile.toPath()
        val reportsOutput = reportDirectory.get().asFile.toPath()
        val stagingRoot = temporaryDir.toPath().resolve("atomic-output")
        resetDirectory(stagingRoot)
        val destination = stagingRoot.resolve("classes")
        val nativeInputs = stagingRoot.resolve("native-input")
        val reports = stagingRoot.resolve("reports")
        resetDirectory(destination)
        resetDirectory(nativeInputs)
        resetDirectory(reports)
        val inputClassCount = sources.count { source -> source.source.fileName.toString().endsWith(CLASS_SUFFIX) }
        val classNames =
            if (settings.enabled) {
                sources.filter { source -> source.source.fileName.toString().endsWith(CLASS_SUFFIX) }
                    .map { source -> ClassReader(Files.readAllBytes(source.source)).className }
            } else {
                emptyList()
            }
        val selection = settings.analyzeClasses(classNames, inputClassCount)
        selection.warningMessages().forEach(logger::warn)
        if (!settings.enabled) {
            copyUnchanged(sources, destination)
            writeReport(
                reports,
                TransformReport(
                    enabled = false,
                    strictStringCoverage = settings.strictStringCoverage,
                    runtimeTarget = DISABLED_STRGUARD_VALUE,
                    selection = selection,
                    stringCoverage = StringCoverage(),
                    removedMetadata = 0,
                ),
            )
            commitOutputs(destination, destinationOutput, nativeInputs, nativeInputsOutput, reports, reportsOutput)
            logSummary(StringCoverage(), removedMetadata = 0)
            return
        }

        val inputDigest = digestInputs(sources)
        val seed = validatedSeed()
        val nativeTarget = JvmNativeTarget.fromRustTriple(targetTriple.get())
        val vaultBuilder =
            try {
                SecureVaultBuilder(seed, moduleIdentity.get(), inputDigest, nativeTarget)
            } finally {
                inputDigest.fill(0)
            }

        vaultBuilder.use { builder ->
            val metadataMappings = linkedSetOf<String>()
            var stringCoverage = StringCoverage()
            sources.forEach { source ->
                val target = destination.resolve(source.relativePath)
                Files.createDirectories(target.parent)
                if (source.source.fileName.toString().endsWith(".class")) {
                    val originalBytes = Files.readAllBytes(source.source)
                    val className = ClassReader(originalBytes).className
                    if (settings.shouldTransformClass(className)) {
                        val result = ClassTransformer.transform(originalBytes, settings, builder)
                        metadataMappings.addAll(result.metadataMappings)
                        stringCoverage = stringCoverage.plus(result.stringCoverage)
                        Files.write(target, result.bytes)
                    } else {
                        Files.copy(source.source, target)
                    }
                } else {
                    Files.copy(source.source, target)
                }
            }

            check(stringCoverage.protectedStrings == builder.protectedStringCount.toLong()) {
                "StrGuard coverage count does not match generated vault records"
            }
            val report =
                TransformReport(
                    enabled = true,
                    strictStringCoverage = settings.strictStringCoverage,
                    runtimeTarget = nativeTarget.rustTriple,
                    selection = selection,
                    stringCoverage = stringCoverage,
                    removedMetadata = metadataMappings.size,
                )
            handleCoverage(settings, stringCoverage, reports, reportsOutput, report)
            SupportClassFiles.writeRuntime(destination, builder.bridge)
            builder.writeNativeInputs(nativeInputs).close()
            writeReport(reports, report)
            commitOutputs(destination, destinationOutput, nativeInputs, nativeInputsOutput, reports, reportsOutput)
            logSummary(stringCoverage, metadataMappings.size)
        }
    }

    private fun copyUnchanged(sources: List<InputFile>, destination: Path) {
        sources.forEach { source ->
            val target = destination.resolve(source.relativePath)
            Files.createDirectories(target.parent)
            Files.copy(source.source, target)
        }
    }

    private fun writeReport(reports: Path, report: TransformReport) {
        Files.writeString(
            reports.resolve("summary.txt"),
            report.asPropertiesText(),
            StandardCharsets.UTF_8,
        )
    }

    private fun handleCoverage(
        settings: TransformSettings,
        coverage: StringCoverage,
        reports: Path,
        reportsOutput: Path,
        report: TransformReport,
    ) {
        val violation = coverage.strictViolationMessage() ?: return
        if (settings.strictStringCoverage) {
            writeReport(reports, report)
            replaceOutputsAtomically(OutputReplacement(reports, reportsOutput))
            throw GradleException("$violation; strictStringCoverage is enabled")
        }
        logger.warn("$violation; enable strictStringCoverage to fail the build")
    }

    private fun logSummary(stringCoverage: StringCoverage, removedMetadata: Int) {
        if (consoleOutput.get()) {
            logger.lifecycle(
                "StrGuard protected ${stringCoverage.protectedStrings} string locations, skipped " +
                    "${stringCoverage.skippedStrings}, and removed $removedMetadata metadata annotations",
            )
        }
    }

    private fun validatedSeed(): String {
        val seed = releaseSeedHex.orNull
            ?: throw GradleException(
                "StrGuard requires strGuard.releaseSeedHex or STRGUARD_RELEASE_SEED_HEX",
            )
        val seedBytes =
            try {
                CryptoPrimitives.parseHex256(seed)
            } catch (failure: IllegalArgumentException) {
                throw GradleException(failure.message ?: "Invalid StrGuard release seed", failure)
            }
        try {
            val fingerprint = CryptoPrimitives.sha256(seedBytes)
            val actualFingerprint =
                try {
                    CryptoPrimitives.hex(fingerprint)
                } finally {
                    fingerprint.fill(0)
                }
            if (actualFingerprint != releaseSeedFingerprint.get()) {
                throw GradleException("StrGuard release seed fingerprint does not match the configured seed")
            }
            return seed
        } finally {
            seedBytes.fill(0)
        }
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

    private fun commitOutputs(
        destination: Path,
        destinationOutput: Path,
        nativeInputs: Path,
        nativeInputsOutput: Path,
        reports: Path,
        reportsOutput: Path,
    ) {
        replaceOutputsAtomically(
            OutputReplacement(destination, destinationOutput),
            OutputReplacement(nativeInputs, nativeInputsOutput),
            OutputReplacement(reports, reportsOutput),
        )
    }
}

private data class InputFile(
    val source: Path,
    val relativePath: Path,
    val normalizedRelativePath: String,
)

private const val CLASS_SUFFIX = ".class"
