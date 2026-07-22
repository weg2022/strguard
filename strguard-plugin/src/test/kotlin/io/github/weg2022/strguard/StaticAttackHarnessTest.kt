package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import io.github.weg2022.strguard.vault.SecureVaultBuilder
import io.github.weg2022.strguard.vault.VAULT_FILE_NAME
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.*

class StaticAttackHarnessTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `legacy bytecode is recoverable while protected artifacts have no direct secret material`() {
        val legacyClass = legacyClass(SECRET, LEGACY_KEY)
        assertEquals(listOf(SECRET), LegacyBytecodeAttacker.recover(legacyClass))

        val originalClass = plaintextClass(SECRET)
        val vaultBuilder =
            SecureVaultBuilder(
                TEST_RELEASE_SEED,
                "io.github.weg2022:attack-fixture::attack-fixture",
                CryptoPrimitives.sha256(originalClass),
                JvmNativeTarget.WINDOWS_X64,
            )
        val transformed =
            ClassTransformer.transform(
                originalClass,
                TransformSettings(
                    enabled = true,
                    java9StringConcatEnabled = true,
                    removeMetadata = false,
                    stringGuardPackages = listOf("sample"),
                    keepStringPackages = emptyList(),
                    removeMetadataPackages = emptyList(),
                    keepMetadataPackages = emptyList(),
                ),
                vaultBuilder,
            )
        vaultBuilder.writeNativeInputs(temporaryDirectory).close()
        vaultBuilder.close()
        val vault = Files.readAllBytes(temporaryDirectory.resolve(VAULT_FILE_NAME))

        assertTrue(LegacyBytecodeAttacker.recover(transformed.bytes).isEmpty())
        assertFalse(transformed.bytes.asText().contains(SECRET))
        assertFalse(transformed.bytes.asText().contains(TEST_RELEASE_SEED))
        assertFalse(vault.asText().contains(SECRET))
        assertFalse(vault.asText().contains(TEST_RELEASE_SEED))
    }
}

private object LegacyBytecodeAttacker {
    fun recover(classBytes: ByteArray): List<String> {
        val recovered = mutableListOf<String>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = LegacyArrayInterpreter(recovered)
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return recovered
    }
}

private class LegacyArrayInterpreter(
    private val recovered: MutableList<String>,
) : MethodVisitor(Opcodes.ASM9) {
    private val stack = ArrayDeque<Any>()

    override fun visitInsn(opcode: Int) {
        when (opcode) {
            Opcodes.ICONST_M1 -> stack.addLast(-1)

            in Opcodes.ICONST_0..Opcodes.ICONST_5 -> stack.addLast(opcode - Opcodes.ICONST_0)

            Opcodes.DUP -> stack.addLast(stack.peekLast())

            Opcodes.BASTORE -> {
                val value = stack.removeLast() as Int
                val index = stack.removeLast() as Int
                val array = stack.removeLast() as ByteArray
                array[index] = value.toByte()
            }
        }
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        when (opcode) {
            Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.addLast(operand)

            Opcodes.NEWARRAY -> {
                check(operand == Opcodes.T_BYTE)
                stack.addLast(ByteArray(stack.removeLast() as Int))
            }
        }
    }

    override fun visitLdcInsn(value: Any?) {
        if (value is Int) {
            stack.addLast(value)
        }
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) {
        if (opcode != Opcodes.INVOKESTATIC || name != "decode" || descriptor != "([B[B)Ljava/lang/String;") {
            return
        }
        val key = stack.removeLast() as ByteArray
        val ciphertext = stack.removeLast() as ByteArray
        val plaintext = xor(ciphertext, key).toString(StandardCharsets.UTF_8)
        recovered += plaintext
        stack.addLast(plaintext)
    }
}

@Suppress("SameParameterValue")
private fun legacyClass(plaintext: String, key: ByteArray): ByteArray {
    val writer = newClassWriter()
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "reveal", "()Ljava/lang/String;", null, null).apply {
        visitCode()
        pushByteArray(this, xor(plaintext.toByteArray(StandardCharsets.UTF_8), key))
        pushByteArray(this, key)
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "org/prime4j/strguard/api/StrGuard",
            "decode",
            "([B[B)Ljava/lang/String;",
            false,
        )
        visitInsn(Opcodes.ARETURN)
        visitMaxs(0, 0)
        visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
}

@Suppress("SameParameterValue")
private fun plaintextClass(plaintext: String): ByteArray {
    val writer = newClassWriter()
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "reveal", "()Ljava/lang/String;", null, null).apply {
        visitCode()
        visitLdcInsn(plaintext)
        visitInsn(Opcodes.ARETURN)
        visitMaxs(0, 0)
        visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
}

private fun newClassWriter(): ClassWriter = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
    visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Secrets", null, "java/lang/Object", null)
}

private fun pushByteArray(method: MethodVisitor, bytes: ByteArray) {
    method.visitLdcInsn(bytes.size)
    method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
    bytes.forEachIndexed { index, byte ->
        method.visitInsn(Opcodes.DUP)
        method.visitLdcInsn(index)
        method.visitLdcInsn(byte.toInt())
        method.visitInsn(Opcodes.BASTORE)
    }
}

private fun xor(value: ByteArray, key: ByteArray): ByteArray = ByteArray(value.size) { index -> (value[index].toInt() xor key[index % key.size].toInt()).toByte() }

private fun ByteArray.asText(): String = toString(StandardCharsets.ISO_8859_1)

private const val SECRET = "attack-harness-sensitive-value"
private val LEGACY_KEY = "legacy-hard-coded-key".toByteArray(StandardCharsets.UTF_8)
private const val TEST_RELEASE_SEED =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
