package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.*
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

@CacheableTask
abstract class TransformAndroidClassesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

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
        val entries = collectEntries()
        val output = outputJar.get().asFile.toPath()
        val nativeInputsOutput = nativeInputDirectory.get().asFile.toPath()
        val reportsOutput = reportDirectory.get().asFile.toPath()
        val stagingRoot = temporaryDir.toPath().resolve("atomic-output")
        resetDirectory(stagingRoot)
        val stagedOutput = stagingRoot.resolve("classes.jar")
        val nativeInputs = stagingRoot.resolve("native-input")
        val reports = stagingRoot.resolve("reports")
        resetDirectory(nativeInputs)
        resetDirectory(reports)
        val classEntries = entries.filterKeys { entryName -> entryName.endsWith(CLASS_SUFFIX) }
        val classNames =
            if (settings.enabled) {
                classEntries.values.map { classBytes -> ClassReader(classBytes).className }
            } else {
                emptyList()
            }
        val selection = settings.analyzeClasses(classNames, classEntries.size)
        selection.warningMessages().forEach(logger::warn)
        if (!settings.enabled) {
            writeOutputJar(entries, stagedOutput)
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
            commitOutputs(stagedOutput, output, nativeInputs, nativeInputsOutput, reports, reportsOutput)
            logSummary(StringCoverage(), removedMetadata = 0)
            return
        }

        val inputDigest = digestInputs(entries)
        val vaultBuilder =
            try {
                SecureVaultBuilder(
                    validatedSeed(),
                    moduleIdentity.get(),
                    inputDigest,
                    AndroidVaultTarget,
                )
            } finally {
                inputDigest.fill(0)
            }
        vaultBuilder.use { builder ->
            val metadataMappings = linkedSetOf<String>()
            var stringCoverage = StringCoverage()
            val outputEntries = TreeMap<String, ByteArray>()
            entries.forEach { (entryName, originalBytes) ->
                val outputBytes =
                    if (entryName.endsWith(CLASS_SUFFIX)) {
                        val className = ClassReader(originalBytes).className
                        if (settings.shouldTransformClass(className)) {
                            val result = ClassTransformer.transform(originalBytes, settings, builder)
                            metadataMappings.addAll(result.metadataMappings)
                            stringCoverage = stringCoverage.plus(result.stringCoverage)
                            result.bytes
                        } else {
                            originalBytes
                        }
                    } else {
                        originalBytes
                    }
                outputEntries[entryName] = outputBytes
            }

            check(stringCoverage.protectedStrings == builder.protectedStringCount.toLong()) {
                "StrGuard coverage count does not match generated vault records"
            }
            val report =
                TransformReport(
                    enabled = true,
                    strictStringCoverage = settings.strictStringCoverage,
                    runtimeTarget = AndroidVaultTarget.vaultIdentity,
                    selection = selection,
                    stringCoverage = stringCoverage,
                    removedMetadata = metadataMappings.size,
                )
            handleCoverage(settings, stringCoverage, reports, reportsOutput, report)
            addSupportClasses(outputEntries, builder)
            builder.writeNativeInputs(nativeInputs).close()
            writeOutputJar(outputEntries, stagedOutput)
            writeReport(reports, report)
            commitOutputs(stagedOutput, output, nativeInputs, nativeInputsOutput, reports, reportsOutput)
            logSummary(stringCoverage, metadataMappings.size)
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
                "StrGuard protected ${stringCoverage.protectedStrings} Android string locations, skipped " +
                    "${stringCoverage.skippedStrings}, and removed $removedMetadata metadata annotations",
            )
        }
    }

    private fun collectEntries(): TreeMap<String, ByteArray> {
        val entries = TreeMap<String, ByteArray>()
        inputJars.get().map { it.asFile.toPath() }.sortedBy(Path::toString).forEach { jarPath ->
            JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filterNot { it.isDirectory || shouldDiscardJarEntry(it.name) }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        addEntry(entries, entry.name, jar.getInputStream(entry).use { it.readBytes() }, jarPath)
                    }
            }
        }
        inputDirectories.get().map { it.asFile.toPath() }.sortedBy(Path::toString).forEach { root ->
            if (!Files.isDirectory(root)) {
                return@forEach
            }
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach { source ->
                        val entryName = root.relativize(source).toString().replace('\\', '/')
                        addEntry(entries, entryName, Files.readAllBytes(source), root)
                    }
            }
        }
        return entries
    }

    private fun addEntry(
        entries: MutableMap<String, ByteArray>,
        entryName: String,
        bytes: ByteArray,
        source: Path,
    ) {
        val existing = entries.putIfAbsent(entryName, bytes) ?: return
        if (!existing.contentEquals(bytes)) {
            throw GradleException("StrGuard found conflicting Android class entry $entryName from $source")
        }
    }

    private fun digestInputs(entries: Map<String, ByteArray>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (entryName, bytes) ->
            val pathBytes = entryName.toByteArray(StandardCharsets.UTF_8)
            digest.update(intLe(pathBytes.size))
            digest.update(pathBytes)
            digest.update(intLe(bytes.size))
            digest.update(bytes)
        }
        return digest.digest()
    }

    private fun addSupportClasses(
        entries: MutableMap<String, ByteArray>,
        vaultBuilder: SecureVaultBuilder,
    ) {
        val supportDirectory = temporaryDir.toPath().resolve("support-classes")
        resetDirectory(supportDirectory)
        SupportClassFiles.writeRuntime(supportDirectory, vaultBuilder.bridge)
        Files.walk(supportDirectory).use { paths ->
            paths.filter(Files::isRegularFile).forEach { source ->
                val entryName = supportDirectory.relativize(source).toString().replace('\\', '/')
                check(entries.putIfAbsent(entryName, Files.readAllBytes(source)) == null) {
                    "StrGuard support class conflicts with Android class entry $entryName"
                }
            }
        }
    }

    private fun writeOutputJar(entries: Map<String, ByteArray>, output: Path) {
        Files.createDirectories(output.parent)
        JarOutputStream(BufferedOutputStream(Files.newOutputStream(output))).use { jar ->
            entries.forEach { (entryName, bytes) ->
                val entry = ZipEntry(entryName)
                entry.time = 0L
                jar.putNextEntry(entry)
                jar.write(bytes)
                jar.closeEntry()
            }
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

    private fun resetDirectory(directory: Path) {
        directory.toFile().deleteRecursively()
        Files.createDirectories(directory)
    }

    private fun commitOutputs(
        stagedOutput: Path,
        output: Path,
        nativeInputs: Path,
        nativeInputsOutput: Path,
        reports: Path,
        reportsOutput: Path,
    ) {
        replaceOutputsAtomically(
            OutputReplacement(stagedOutput, output),
            OutputReplacement(nativeInputs, nativeInputsOutput),
            OutputReplacement(reports, reportsOutput),
        )
    }

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}

private fun shouldDiscardJarEntry(entryName: String): Boolean {
    val upperCaseName = entryName.uppercase()
    return upperCaseName == "META-INF/MANIFEST.MF" ||
        upperCaseName.endsWith(".SF") ||
        upperCaseName.endsWith(".RSA") ||
        upperCaseName.endsWith(".DSA")
}

private const val CLASS_SUFFIX = ".class"
