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

    @Test
    fun `package selectors are normalized deduplicated and sorted`() {
        val settings = settings(
            enabled = true,
            removeMetadata = true,
            stringGuardPackages = listOf(" sample.other/ ", "/sample.app", "sample.app"),
            keepStringPackages = listOf("sample.app.public/"),
        )

        assertEquals(listOf("sample/app", "sample/other"), settings.stringGuardPackages)
        assertEquals(listOf("sample/app/public"), settings.keepStringPackages)
        assertTrue(settings.shouldTransformStrings("sample/app/internal/Secret"))
    }

    @Test
    fun `package selectors reject illegal segments`() {
        listOf("sample..internal", "sample/not-valid", "sample/9invalid", ".").forEach { selector ->
            val failure = assertFailsWith<IllegalArgumentException> {
                settings(
                    enabled = true,
                    removeMetadata = false,
                    stringGuardPackages = listOf(selector),
                )
            }

            assertContains(failure.message.orEmpty(), "stringGuardPackages")
            assertContains(failure.message.orEmpty(), selector)
        }
    }

    @Test
    fun `explicit includes are validated against eligible classes`() {
        val settings = settings(
            enabled = true,
            removeMetadata = false,
            stringGuardPackages = listOf("sample.empty"),
        )

        val summary = settings.analyzeClasses(
            listOf("sample/empty/NoLiteral", "sample/other/Other"),
        )

        assertEquals(2, summary.inputClasses)
        assertEquals(2, summary.eligibleClasses)
        assertEquals(1, summary.matchedClasses)
        assertEquals(1, summary.skippedClasses)
        val failure = assertFailsWith<IllegalArgumentException> {
            settings(
                enabled = true,
                removeMetadata = false,
                stringGuardPackages = listOf("missing.package"),
            ).analyzeClasses(listOf("sample/empty/NoLiteral"))
        }
        assertContains(failure.message.orEmpty(), "missing/package")
    }

    @Test
    fun `report properties use a deterministic aggregate-only schema`() {
        val report =
            TransformReport(
                enabled = true,
                strictStringCoverage = true,
                runtimeTarget = "x86_64-unknown-linux-gnu",
                selection =
                ClassSelectionSummary(
                    inputClasses = 5,
                    eligibleClasses = 4,
                    matchedClasses = 2,
                    skippedClasses = 2,
                    unmatchedKeepStringPackages = listOf("sample/unused"),
                    unmatchedKeepMetadataPackages = listOf("sample/metadata"),
                ),
                stringCoverage =
                StringCoverage(
                    protectedStrings = 7,
                    skippedCounts =
                    mapOf(
                        StringSkipReason.EMPTY_STRING to 1,
                        StringSkipReason.ANNOTATION_STRING to 2,
                    ),
                    coverageUnknowns = 1,
                ),
                removedMetadata = 1,
            )

        assertEquals(
            """
            schemaVersion=1
            enabled=true
            strictStringCoverage=true
            runtimeTarget=x86_64-unknown-linux-gnu
            inputClasses=5
            eligibleClasses=4
            matchedClasses=2
            skippedClasses=2
            stringCandidates=10
            protectedStrings=7
            skippedStrings=3
            strictViolations=3
            coverageUnknowns=1
            skippedEmptyStrings=1
            skippedOversizedStrings=0
            skippedAnnotationStrings=2
            skippedConstantDynamicStrings=0
            skippedDisabledStringConcats=0
            skippedUnsupportedStringConcats=0
            skippedUnsupportedInvokeDynamics=0
            skippedUnsupportedFieldStrings=0
            removedMetadata=1
            unmatchedKeepStringPackages=sample/unused
            unmatchedKeepMetadataPackages=sample/metadata

            """.trimIndent(),
            report.asPropertiesText(),
        )
    }

    private fun settings(
        enabled: Boolean,
        removeMetadata: Boolean,
        stringGuardPackages: List<String> = emptyList(),
        keepStringPackages: List<String> = emptyList(),
        removeMetadataPackages: List<String> = emptyList(),
        keepMetadataPackages: List<String> = emptyList(),
    ): TransformSettings = TransformSettings(
        enabled = enabled,
        java9StringConcatEnabled = true,
        removeMetadata = removeMetadata,
        stringGuardPackages = stringGuardPackages,
        keepStringPackages = keepStringPackages,
        removeMetadataPackages = removeMetadataPackages,
        keepMetadataPackages = keepMetadataPackages,
    )
}
