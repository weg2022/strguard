package io.github.weg2022.strguard

import kotlin.test.*

class NativeTargetTest {
    @Test
    fun `maps all supported JVM Native targets`() {
        val expectations =
            listOf(
                JvmTargetExpectation(
                    target = JvmNativeTarget.WINDOWS_X64,
                    rustTriple = "x86_64-pc-windows-msvc",
                    packagingDirectory = "windows-x86_64",
                    packagedLibraryFileName = "sg_fixture.dll",
                    cargoLibraryFileName = "strguard_native.dll",
                ),
                JvmTargetExpectation(
                    target = JvmNativeTarget.WINDOWS_ARM64,
                    rustTriple = "aarch64-pc-windows-msvc",
                    packagingDirectory = "windows-arm64",
                    packagedLibraryFileName = "sg_fixture.dll",
                    cargoLibraryFileName = "strguard_native.dll",
                ),
                JvmTargetExpectation(
                    target = JvmNativeTarget.LINUX_GLIBC_X64,
                    rustTriple = "x86_64-unknown-linux-gnu",
                    packagingDirectory = "linux-x86_64",
                    packagedLibraryFileName = "libsg_fixture.so",
                    cargoLibraryFileName = "libstrguard_native.so",
                ),
                JvmTargetExpectation(
                    target = JvmNativeTarget.LINUX_GLIBC_ARM64,
                    rustTriple = "aarch64-unknown-linux-gnu",
                    packagingDirectory = "linux-arm64",
                    packagedLibraryFileName = "libsg_fixture.so",
                    cargoLibraryFileName = "libstrguard_native.so",
                ),
                JvmTargetExpectation(
                    target = JvmNativeTarget.MACOS_X64,
                    rustTriple = "x86_64-apple-darwin",
                    packagingDirectory = "macos-x86_64",
                    packagedLibraryFileName = "libsg_fixture.dylib",
                    cargoLibraryFileName = "libstrguard_native.dylib",
                ),
                JvmTargetExpectation(
                    target = JvmNativeTarget.MACOS_ARM64,
                    rustTriple = "aarch64-apple-darwin",
                    packagingDirectory = "macos-arm64",
                    packagedLibraryFileName = "libsg_fixture.dylib",
                    cargoLibraryFileName = "libstrguard_native.dylib",
                ),
            )

        assertEquals(expectations.size, JvmNativeTarget.entries.size)
        expectations.forEach { expected ->
            val target = JvmNativeTarget.fromRustTriple(" ${expected.rustTriple.uppercase()} ")
            assertEquals(expected.target, target)
            assertEquals(expected.packagingDirectory, target.packagingDirectory)
            assertEquals(expected.packagedLibraryFileName, target.packagedLibraryFileName("fixture"))
            assertEquals(expected.cargoLibraryFileName, target.cargoLibraryFileName)
            assertEquals("sg_fixture", target.libraryLoadName("fixture"))
            assertEquals(
                "META-INF/strguard/native/${expected.packagingDirectory}/${expected.packagedLibraryFileName}",
                target.packagedResourcePath(expected.packagedLibraryFileName),
            )
        }
    }

    @Test
    fun `detects x64 and arm64 JVM hosts`() {
        assertEquals(JvmNativeTarget.WINDOWS_X64, JvmNativeTarget.detectHost("Windows 11", "amd64"))
        assertEquals(JvmNativeTarget.WINDOWS_ARM64, JvmNativeTarget.detectHost("Windows 11", "ARM64"))
        assertEquals(JvmNativeTarget.LINUX_GLIBC_X64, JvmNativeTarget.detectHost("Linux", "x86_64"))
        assertEquals(JvmNativeTarget.LINUX_GLIBC_ARM64, JvmNativeTarget.detectHost("Linux", "aarch64"))
        assertEquals(JvmNativeTarget.MACOS_X64, JvmNativeTarget.detectHost("Mac OS X", "x86-64"))
        assertEquals(JvmNativeTarget.MACOS_ARM64, JvmNativeTarget.detectHost("Darwin", "arm64"))
    }

    @Test
    fun `rejects Android and unsupported JVM targets`() {
        assertFailsWith<IllegalArgumentException> {
            JvmNativeTarget.fromRustTriple(AndroidAbi.ARM64_V8A.rustTriple)
        }

        val targetFailure =
            assertFailsWith<IllegalArgumentException> {
                JvmNativeTarget.fromRustTriple("riscv64-unknown-linux-gnu")
            }
        assertTrue(targetFailure.message.orEmpty().contains("Supported targets"))

        assertFailsWith<IllegalArgumentException> {
            JvmNativeTarget.detectHost("Linux", "riscv64")
        }
    }

    private data class JvmTargetExpectation(
        val target: JvmNativeTarget,
        val rustTriple: String,
        val packagingDirectory: String,
        val packagedLibraryFileName: String,
        val cargoLibraryFileName: String,
    )
}
