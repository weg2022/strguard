package io.github.weg2022.strguard

import kotlin.test.*

class TransformSettingsTest {
    @Test
    fun `enabled is a total transformation switch`() {
        val settings = settings(
            enabled = false,
            removeMetadata = true,
            stringGuardPackages = listOf("sample"),
            removeMetadataPackages = listOf("sample"),
        )

        assertFalse(settings.shouldTransformStrings("sample/Example"))
        assertFalse(settings.shouldRemoveMetadata("sample/Example"))
        assertFalse(settings.shouldTransformClass("sample/Example"))
    }

    @Test
    fun `package matching respects segment boundaries and keep rules`() {
        val settings = settings(
            enabled = true,
            removeMetadata = true,
            stringGuardPackages = listOf("sample.app"),
            keepStringPackages = listOf("sample.app.public"),
            removeMetadataPackages = listOf("sample.app"),
            keepMetadataPackages = listOf("sample.app.reflective"),
        )

        assertTrue(settings.shouldTransformStrings("sample/app/internal/Secret"))
        assertFalse(settings.shouldTransformStrings("sample/application/NotIncluded"))
        assertFalse(settings.shouldTransformStrings("sample/app/public/Api"))
        assertTrue(settings.shouldRemoveMetadata("sample/app/internal/Secret"))
        assertFalse(settings.shouldRemoveMetadata("sample/app/reflective/Model"))
        assertFalse(settings.shouldTransformClass("io/github/weg2022/strguard/generated/Bridge"))
    }

    private fun settings(
        enabled: Boolean,
        removeMetadata: Boolean,
        stringGuardPackages: List<String> = emptyList(),
        keepStringPackages: List<String> = emptyList(),
        removeMetadataPackages: List<String> = emptyList(),
        keepMetadataPackages: List<String> = emptyList(),
    ): TransformSettings =
        TransformSettings(
            enabled = enabled,
            java9StringConcatEnabled = true,
            removeMetadata = removeMetadata,
            stringGuardPackages = stringGuardPackages,
            keepStringPackages = keepStringPackages,
            removeMetadataPackages = removeMetadataPackages,
            keepMetadataPackages = keepMetadataPackages,
        )
}
