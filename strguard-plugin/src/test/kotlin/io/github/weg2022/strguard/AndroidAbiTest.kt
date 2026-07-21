package io.github.weg2022.strguard

import kotlin.test.*

class AndroidAbiTest {
    @Test
    fun `maps all supported Android ABIs`() {
        val expectations =
            listOf(
                AndroidAbiExpectation(
                    abi = AndroidAbi.ARMEABI_V7A,
                    abiName = "armeabi-v7a",
                    rustTriple = "armv7-linux-androideabi",
                    ndkClangStem = "armv7a-linux-androideabi",
                    cargoLinkerEnvKey = "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER",
                ),
                AndroidAbiExpectation(
                    abi = AndroidAbi.ARM64_V8A,
                    abiName = "arm64-v8a",
                    rustTriple = "aarch64-linux-android",
                    ndkClangStem = "aarch64-linux-android",
                    cargoLinkerEnvKey = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
                ),
                AndroidAbiExpectation(
                    abi = AndroidAbi.X86,
                    abiName = "x86",
                    rustTriple = "i686-linux-android",
                    ndkClangStem = "i686-linux-android",
                    cargoLinkerEnvKey = "CARGO_TARGET_I686_LINUX_ANDROID_LINKER",
                ),
                AndroidAbiExpectation(
                    abi = AndroidAbi.X86_64,
                    abiName = "x86_64",
                    rustTriple = "x86_64-linux-android",
                    ndkClangStem = "x86_64-linux-android",
                    cargoLinkerEnvKey = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
                ),
            )

        assertEquals(expectations.size, AndroidAbi.entries.size)
        expectations.forEach { expected ->
            val abi = AndroidAbi.fromAbiName(" ${expected.abiName.uppercase()} ")
            assertEquals(expected.abi, abi)
            assertEquals(expected.rustTriple, abi.rustTriple)
            assertEquals(expected.ndkClangStem, abi.ndkClangStem)
            assertEquals(expected.cargoLinkerEnvKey, abi.cargoLinkerEnvKey)
            assertEquals(expected.abiName, abi.packagingDirectory)
            assertEquals("libsg_fixture.so", abi.packagedLibraryFileName("fixture"))
            assertEquals("libstrguard_native.so", abi.cargoLibraryFileName)
            assertEquals("sg_fixture", abi.libraryLoadName("fixture"))
            assertEquals(
                "${expected.abiName}/libsg_fixture.so",
                abi.packagedResourcePath("libsg_fixture.so"),
            )
            assertEquals(abi, AndroidAbi.fromRustTriple(" ${expected.rustTriple.uppercase()} "))
        }
    }

    @Test
    fun `keeps Android and JVM Rust targets separate`() {
        assertFailsWith<IllegalArgumentException> {
            AndroidAbi.fromRustTriple(JvmNativeTarget.WINDOWS_X64.rustTriple)
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidAbi.fromAbiName("mips")
        }
    }

    private data class AndroidAbiExpectation(
        val abi: AndroidAbi,
        val abiName: String,
        val rustTriple: String,
        val ndkClangStem: String,
        val cargoLinkerEnvKey: String,
    )
}
