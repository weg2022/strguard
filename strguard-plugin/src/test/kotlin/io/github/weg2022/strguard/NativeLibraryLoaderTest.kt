package io.github.weg2022.strguard

import io.github.weg2022.strguard.runtime.NativeLibraryLoader
import kotlin.test.*

class NativeLibraryLoaderTest {
    @Test
    fun `missing runtime resource reports requested path`() {
        val resourcePath = "META-INF/strguard/native/missing/libsg_missing.so"

        val failure = assertFailsWith<IllegalStateException> {
            NativeLibraryLoader.extract(NativeLibraryLoaderTest::class.java, resourcePath, "libsg_missing.so")
        }

        assertContains(failure.message.orEmpty(), resourcePath)
    }
}
