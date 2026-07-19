package io.github.weg2022.strguard

import kotlin.test.*

class NativeTargetTest {
    @Test
    fun `maps supported Rust targets to packaged libraries`() {
        val expectations =
            mapOf(
                "x86_64-pc-windows-msvc" to Triple("windows-x86_64", "sg_fixture.dll", "strguard_native.dll"),
                "x86_64-unknown-linux-gnu" to Triple("linux-x86_64", "libsg_fixture.so", "libstrguard_native.so"),
                "x86_64-apple-darwin" to Triple("macos-x86_64", "libsg_fixture.dylib", "libstrguard_native.dylib"),
                "aarch64-apple-darwin" to Triple("macos-arm64", "libsg_fixture.dylib", "libstrguard_native.dylib"),
                "aarch64-linux-android" to Triple("arm64-v8a", "libsg_fixture.so", "libstrguard_native.so"),
            )

        expectations.forEach { (triple, expected) ->
            val target = NativeTarget.fromRustTriple(triple)
            assertEquals(expected.first, target.resourceDirectory)
            assertEquals(expected.second, target.packagedLibraryFileName("fixture"))
            assertEquals(expected.third, target.cargoLibraryFileName)
        }
    }

    @Test
    fun `detects supported host aliases`() {
        assertEquals(NativeTarget.WINDOWS_X64, NativeTarget.detectHost("Windows 11", "amd64"))
        assertEquals(NativeTarget.LINUX_X64, NativeTarget.detectHost("Linux", "x86_64"))
        assertEquals(NativeTarget.MACOS_X64, NativeTarget.detectHost("Mac OS X", "x86-64"))
        assertEquals(NativeTarget.MACOS_ARM64, NativeTarget.detectHost("Darwin", "aarch64"))
    }

    @Test
    fun `rejects unsupported target and host`() {
        val targetFailure =
            assertFailsWith<IllegalArgumentException> {
                NativeTarget.fromRustTriple("riscv64-unknown-linux-gnu")
            }
        assertTrue(targetFailure.message.orEmpty().contains("Supported targets"))

        assertFailsWith<IllegalArgumentException> {
            NativeTarget.detectHost("Linux", "riscv64")
        }
    }
}
