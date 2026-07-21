package io.github.weg2022.strguard

import kotlin.test.*

class RuntimeFamilyTest {
    @Test
    fun `keeps JVM and Android packaging behavior independent`() {
        assertEquals(setOf(RuntimeFamily.JVM, RuntimeFamily.ANDROID), RuntimeFamily.entries.toSet())
        assertTrue(RuntimeFamily.JVM.extractFromResources)
        assertFalse(RuntimeFamily.ANDROID.extractFromResources)
        assertEquals(
            "META-INF/strguard/native/linux-x86_64/libsg_fixture.so",
            RuntimeFamily.JVM.packagedResourcePath("linux-x86_64", "libsg_fixture.so"),
        )
        assertEquals(
            "arm64-v8a/libsg_fixture.so",
            RuntimeFamily.ANDROID.packagedResourcePath("arm64-v8a", "libsg_fixture.so"),
        )
    }
}
