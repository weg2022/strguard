package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TreeMap
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal object StrGuardShrunkArtifactFinalizer {
    fun finalize(
        protectedJar: Path,
        shrunkJar: Path,
        verifiedJar: Path,
        shrinkerId: String,
    ) {
        val protectedEntries = readEntries(protectedJar)
        val markerEntries =
            protectedEntries.keys.filter { entry ->
                entry.startsWith("$STRGUARD_ARTIFACT_MARKER_DIRECTORY/") && entry.endsWith(".properties")
            }
        if (markerEntries.size != 1) {
            throw GradleException(
                "StrGuard protected JAR must contain exactly one artifact marker, found ${markerEntries.size}",
            )
        }
        val metadata =
            StrGuardArtifactMetadata.parse(
                ByteArrayInputStream(protectedEntries.getValue(markerEntries.single())),
            )
        if (metadata.stage != STRGUARD_ARTIFACT_STAGE_PROTECTED) {
            throw GradleException("StrGuard shrinker input is not a protected-stage artifact")
        }

        val outputEntries = readEntries(shrunkJar)
        verifyOutputMarker(outputEntries, metadata)
        verifyGeneratedClass(outputEntries, metadata.bridgeClass, metadata.gatewayNames)
        if (metadata.loaderClass.isNotEmpty()) {
            verifyGeneratedClass(outputEntries, metadata.loaderClass, emptyList())
        }
        metadata.nativeResources.forEach { (resourcePath, expectedHash) ->
            val protectedResource = protectedEntries[resourcePath]
                ?: throw GradleException("StrGuard protected JAR is missing Native resource $resourcePath")
            if (sha256(protectedResource) != expectedHash) {
                throw GradleException("StrGuard protected Native resource hash does not match its marker")
            }
            val shrunkResource = outputEntries[resourcePath]
            if (shrunkResource == null) {
                outputEntries[resourcePath] = protectedResource
            } else if (sha256(shrunkResource) != expectedHash) {
                throw GradleException("Shrinker modified StrGuard Native resource $resourcePath")
            }
        }

        outputEntries.remove(metadata.markerPath)
        outputEntries.remove(metadata.embeddedRulesPath)
        val verifiedMetadata = metadata.withShrunkStage(shrinkerId)
        outputEntries[verifiedMetadata.markerPath] = verifiedMetadata.asPropertiesText().toByteArray(Charsets.UTF_8)
        outputEntries[verifiedMetadata.embeddedRulesPath] = StrGuardShrinkerRules.text.toByteArray(Charsets.UTF_8)

        val destination = verifiedJar.toAbsolutePath().normalize()
        val source = shrunkJar.toAbsolutePath().normalize()
        if (destination == source) {
            val temporary = destination.resolveSibling(".${destination.fileName}.strguard.tmp")
            writeEntries(temporary, outputEntries)
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } else {
            writeEntries(destination, outputEntries)
        }
    }

    fun hasProtectedMarker(path: Path): Boolean = ZipFile(path.toFile()).use { archive ->
        archive.entries().asSequence().any { entry ->
            entry.name.startsWith("$STRGUARD_ARTIFACT_MARKER_DIRECTORY/") &&
                entry.name.endsWith(".properties")
        }
    }

    fun containsClass(path: Path, internalClassName: String): Boolean = ZipFile(path.toFile()).use { archive -> archive.getEntry("$internalClassName.class") != null }

    fun readProtectedMetadata(path: Path): StrGuardArtifactMetadata {
        ZipFile(path.toFile()).use { archive ->
            val markers =
                archive.entries().asSequence().filter { entry ->
                    entry.name.startsWith("$STRGUARD_ARTIFACT_MARKER_DIRECTORY/") &&
                        entry.name.endsWith(".properties")
                }.toList()
            if (markers.size != 1) {
                throw GradleException("StrGuard expected one protected marker in $path, found ${markers.size}")
            }
            return StrGuardArtifactMetadata.parse(archive.getInputStream(markers.single())).also { metadata ->
                if (metadata.stage != STRGUARD_ARTIFACT_STAGE_PROTECTED) {
                    throw GradleException("StrGuard artifact $path is not in protected stage")
                }
            }
        }
    }

    private fun verifyGeneratedClass(
        entries: Map<String, ByteArray>,
        internalClassName: String,
        requiredNativeMethods: List<String>,
    ) {
        val bytes = entries["$internalClassName.class"]
            ?: throw GradleException("Shrinker removed or renamed StrGuard class $internalClassName")
        if (requiredNativeMethods.isEmpty()) return
        val nativeMethods = mutableSetOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and Opcodes.ACC_NATIVE != 0 && name != null) nativeMethods += name
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        val missing = requiredNativeMethods.filterNot(nativeMethods::contains)
        if (missing.isNotEmpty()) {
            throw GradleException("Shrinker removed or renamed StrGuard Native gateways: ${missing.joinToString()}")
        }
    }

    private fun verifyOutputMarker(
        entries: Map<String, ByteArray>,
        expected: StrGuardArtifactMetadata,
    ) {
        val marker = entries[expected.markerPath] ?: return
        val actual = StrGuardArtifactMetadata.parse(ByteArrayInputStream(marker))
        if (actual.stage != STRGUARD_ARTIFACT_STAGE_PROTECTED) {
            throw GradleException("Shrinker output is already a finalized StrGuard artifact")
        }
        if (actual != expected) {
            throw GradleException("Shrinker modified the StrGuard protected artifact marker")
        }
    }

    private fun readEntries(path: Path): TreeMap<String, ByteArray> {
        val entries = TreeMap<String, ByteArray>()
        ZipFile(path.toFile()).use { archive ->
            archive.entries().asSequence()
                .filterNot { entry -> entry.isDirectory || isSignatureEntry(entry.name) }
                .forEach { entry -> entries[entry.name] = archive.getInputStream(entry).use { it.readBytes() } }
        }
        return entries
    }

    private fun writeEntries(path: Path, entries: Map<String, ByteArray>) {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path).buffered()).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = 0L
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun isSignatureEntry(path: String): Boolean {
        val upper = path.uppercase()
        return upper.startsWith("META-INF/") &&
            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}
