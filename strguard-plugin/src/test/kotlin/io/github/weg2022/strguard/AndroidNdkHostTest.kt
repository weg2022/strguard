package io.github.weg2022.strguard

import kotlin.test.*

class AndroidNdkHostTest {
    @Test
    fun `maps supported NDK hosts`() {
        assertEquals("windows-x86_64", AndroidNdkHost.detect("Windows 11", "amd64").tag)
        assertEquals("windows-x86_64", AndroidNdkHost.detect("Windows 11", "arm64").tag)
        assertEquals("linux-x86_64", AndroidNdkHost.detect("Linux", "x86_64").tag)
        assertEquals("linux-x86_64", AndroidNdkHost.detect("Linux", "aarch64").tag)
        assertEquals("darwin-x86_64", AndroidNdkHost.detect("Mac OS X", "x86-64").tag)
        assertEquals("darwin-x86_64", AndroidNdkHost.detect("Darwin", "arm64").tag)
    }

    @Test
    fun `maps NDK tool names for each host family`() {
        assertEquals(
            "armv7a-linux-androideabi21-clang.cmd",
            AndroidNdkHost.WINDOWS.clangExecutableName(AndroidAbi.ARMEABI_V7A, 21),
        )
        assertEquals("llvm-ar.exe", AndroidNdkHost.WINDOWS.executableName("llvm-ar"))
        assertEquals(
            "aarch64-linux-android24-clang",
            AndroidNdkHost.LINUX.clangExecutableName(AndroidAbi.ARM64_V8A, 24),
        )
        assertEquals("llvm-ar", AndroidNdkHost.MACOS.executableName("llvm-ar"))
    }

    @Test
    fun `rejects unsupported NDK host`() {
        assertFailsWith<IllegalArgumentException> {
            AndroidNdkHost.detect("Linux", "riscv64")
        }
    }
}
