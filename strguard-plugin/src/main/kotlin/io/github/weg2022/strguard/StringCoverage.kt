package io.github.weg2022.strguard

internal enum class StringSkipReason(
    val reportProperty: String,
    val strictViolation: Boolean = true,
) {
    EMPTY_STRING("skippedEmptyStrings", strictViolation = false),
    OVERSIZED_STRING("skippedOversizedStrings"),
    ANNOTATION_STRING("skippedAnnotationStrings"),
    CONSTANT_DYNAMIC("skippedConstantDynamicStrings"),
    DISABLED_STRING_CONCAT("skippedDisabledStringConcats"),
    UNSUPPORTED_STRING_CONCAT("skippedUnsupportedStringConcats"),
    UNSUPPORTED_INVOKEDYNAMIC("skippedUnsupportedInvokeDynamics"),
    UNSUPPORTED_FIELD_STRING("skippedUnsupportedFieldStrings"),
}

internal data class StringCoverage(
    val protectedStrings: Long = 0,
    internal val skippedCounts: Map<StringSkipReason, Long> = emptyMap(),
    val coverageUnknowns: Long = 0,
) {
    val skippedStrings: Long
        get() = StringSkipReason.entries.sumOf(::skipped)

    val encounteredStrings: Long
        get() = Math.addExact(protectedStrings, skippedStrings)

    val strictViolations: Long
        get() =
            Math.addExact(
                StringSkipReason.entries
                    .filter(StringSkipReason::strictViolation)
                    .sumOf(::skipped),
                coverageUnknowns,
            )

    fun skipped(reason: StringSkipReason): Long = skippedCounts[reason] ?: 0

    fun plus(other: StringCoverage): StringCoverage = StringCoverage(
        protectedStrings = Math.addExact(protectedStrings, other.protectedStrings),
        skippedCounts =
        StringSkipReason.entries.associateWith { reason ->
            Math.addExact(skipped(reason), other.skipped(reason))
        },
        coverageUnknowns = Math.addExact(coverageUnknowns, other.coverageUnknowns),
    )

    fun strictViolationMessage(): String? {
        if (strictViolations == 0L) return null
        val skippedDetails =
            StringSkipReason.entries
                .filter { reason -> reason.strictViolation && skipped(reason) > 0L }
                .joinToString(", ") { reason -> "${reason.reportProperty}=${skipped(reason)}" }
        val details =
            if (coverageUnknowns > 0L) {
                listOf(skippedDetails, "coverageUnknowns=$coverageUnknowns").filter(String::isNotEmpty).joinToString(", ")
            } else {
                skippedDetails
            }
        return "StrGuard found $strictViolations unprotected or unknown string locations ($details)"
    }
}

internal class MutableStringCoverage {
    private var protectedStrings = 0L
    private val skippedCounts = LongArray(StringSkipReason.entries.size)
    private var coverageUnknowns = 0L

    fun recordProtected() {
        protectedStrings = Math.addExact(protectedStrings, 1L)
    }

    fun recordSkipped(reason: StringSkipReason) {
        skippedCounts[reason.ordinal] = Math.addExact(skippedCounts[reason.ordinal], 1L)
    }

    fun recordSkipped(rawValue: String, reason: StringSkipReason) {
        recordSkipped(if (rawValue.isEmpty()) StringSkipReason.EMPTY_STRING else reason)
    }

    fun recordUnknownAttribute() {
        coverageUnknowns = Math.addExact(coverageUnknowns, 1L)
    }

    fun snapshot(): StringCoverage = StringCoverage(
        protectedStrings = protectedStrings,
        skippedCounts = StringSkipReason.entries.associateWith { reason -> skippedCounts[reason.ordinal] },
        coverageUnknowns = coverageUnknowns,
    )
}
