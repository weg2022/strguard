package io.github.weg2022.strguard

import kotlin.test.*

class AndroidNdkHostTest {
    @Test
    fun `maps supported NDK hosts`() {
        assertEquals("windows-x86_64", androidNdkHostTag("Windows 11", "amd64"))
        assertEquals("linux-x86_64", androidNdkHostTag("Linux", "x86_64"))
        assertEquals("darwin-x86_64", androidNdkHostTag("Mac OS X", "x86-64"))
        assertEquals("darwin-x86_64", androidNdkHostTag("Darwin", "arm64"))
    }

    @Test
    fun `rejects unsupported NDK host`() {
        assertFailsWith<IllegalArgumentException> {
            androidNdkHostTag("Linux", "riscv64")
        }
    }
}
