package io.github.weg2022.strguard

import org.gradle.api.GradleException
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
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
        verifyPolicyMarker(protectedEntries, outputEntries)
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

    /**
     * 校验 AI 逆向禁止策略标记在 shrink 后仍可识别。protected jar 中只要有一个类带
     * 注解(即功能开启),shrunk jar 就必须至少一个类仍带——证明 -keepattributes
     * RuntimeInvisibleAnnotations 规则生效;类合并/删除是 shrinker 的正常行为,不追究
     * 单个类的丢失。冗余 StrGuard-AiPolicy attribute 仅告警:注解是主载体,R8 的 DEX
     * 格式无法承载任意 class-file attribute,ProGuard 用户可自行追加 keep 规则。
     */
    private fun verifyPolicyMarker(
        protectedEntries: Map<String, ByteArray>,
        outputEntries: Map<String, ByteArray>,
    ) {
        val markedInProtected = protectedEntries.any { (entryName, bytes) ->
            entryName.endsWith(".class") && hasPolicyAnnotation(bytes)
        }
        if (!markedInProtected) return // 功能未开启,无标记可校验

        val markedInShrunk = outputEntries.any { (entryName, bytes) ->
            entryName.endsWith(".class") && hasPolicyAnnotation(bytes)
        }
        if (!markedInShrunk) {
            throw GradleException(
                "Shrinker removed the StrGuard AI reverse-engineering prohibition marker " +
                    "(${AiPolicyMarker.ALL_ANNOTATION_DESCRIPTORS.joinToString()}); add '-keepattributes " +
                    "RuntimeInvisibleAnnotations' to your ProGuard/R8 configuration",
            )
        }
        val attributeInShrunk = outputEntries.any { (entryName, bytes) ->
            entryName.endsWith(".class") && hasPolicyAttribute(bytes)
        }
        if (!attributeInShrunk) {
            System.err.println(
                "StrGuard: the redundant AI policy attribute ${AiPolicyMarker.ATTRIBUTE_NAME} did not survive " +
                    "shrinking; the RuntimeInvisibleAnnotations marker is still present. Desktop ProGuard users can " +
                    "keep it with '-keepattributes ${AiPolicyMarker.ATTRIBUTE_NAME}'",
            )
        }
    }

    private fun hasPolicyAnnotation(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor in AiPolicyMarker.ALL_ANNOTATION_DESCRIPTORS && !visible) found = true
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有方法级回调。
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor in AiPolicyMarker.ALL_ANNOTATION_DESCRIPTORS && !visible) found = true
                            return null
                        }
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    // 不委托 super:ClassVisitor 默认返回 null,委托会丢失所有字段级回调。
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(annotationDescriptor: String?, visible: Boolean): AnnotationVisitor? {
                            if (annotationDescriptor in AiPolicyMarker.ALL_ANNOTATION_DESCRIPTORS && !visible) found = true
                            return null
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }

    private fun hasPolicyAttribute(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAttribute(attribute: Attribute?) {
                    if (attribute?.type == AiPolicyMarker.ATTRIBUTE_NAME) found = true
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
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
