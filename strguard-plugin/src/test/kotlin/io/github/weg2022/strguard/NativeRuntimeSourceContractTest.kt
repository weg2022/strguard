package io.github.weg2022.strguard

import kotlin.test.*

class NativeRuntimeSourceContractTest {
    @Test
    fun `bundled runtime uses an Android-compatible JNI version`() {
        val resource = assertNotNull(
            javaClass.classLoader.getResource("strguard-native-runtime/src/lib.rs"),
        )
        val source = resource.readText()

        assertContains(source, "JNI_VERSION_1_6")
        assertFalse(source.contains("JNI_VERSION_1_8"))
    }
}
