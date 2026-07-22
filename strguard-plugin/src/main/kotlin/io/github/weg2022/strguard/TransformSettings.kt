package io.github.weg2022.strguard

internal class TransformSettings(
    val enabled: Boolean,
    val java9StringConcatEnabled: Boolean,
    val strictStringCoverage: Boolean = false,
    val removeMetadata: Boolean,
    stringGuardPackages: List<String>,
    keepStringPackages: List<String>,
    removeMetadataPackages: List<String>,
    keepMetadataPackages: List<String>,
) {
    val stringGuardPackages: List<String> =
        normalizePackageSelectors("stringGuardPackages", stringGuardPackages)
    val keepStringPackages: List<String> =
        normalizePackageSelectors("keepStringPackages", keepStringPackages)
    val removeMetadataPackages: List<String> =
        normalizePackageSelectors("removeMetadataPackages", removeMetadataPackages)
    val keepMetadataPackages: List<String> =
        normalizePackageSelectors("keepMetadataPackages", keepMetadataPackages)

    fun shouldTransformClass(internalClassName: String): Boolean = shouldTransformStrings(internalClassName) || shouldRemoveMetadata(internalClassName)

    fun shouldTransformStrings(internalClassName: String): Boolean = isEligibleClass(internalClassName) &&
        matchesIncludedPackages(internalClassName, stringGuardPackages) &&
        !matchesAnyPackage(internalClassName, keepStringPackages)

    fun shouldRemoveMetadata(internalClassName: String): Boolean = isEligibleClass(internalClassName) &&
        removeMetadata &&
        matchesIncludedPackages(internalClassName, removeMetadataPackages) &&
        !matchesAnyPackage(internalClassName, keepMetadataPackages)

    fun analyzeClasses(
        internalClassNames: List<String>,
        inputClassCount: Int = internalClassNames.size,
    ): ClassSelectionSummary {
        if (!enabled) {
            return ClassSelectionSummary(
                inputClasses = inputClassCount,
                eligibleClasses = 0,
                matchedClasses = 0,
                skippedClasses = 0,
                unmatchedKeepStringPackages = emptyList(),
                unmatchedKeepMetadataPackages = emptyList(),
            )
        }

        val eligibleClassNames = internalClassNames.filter(::isEligibleClass)
        validateExplicitIncludes("stringGuardPackages", stringGuardPackages, eligibleClassNames)
        if (removeMetadata) {
            validateExplicitIncludes(
                "removeMetadataPackages",
                removeMetadataPackages,
                eligibleClassNames,
            )
        }
        val matchedClasses = eligibleClassNames.count(::shouldTransformClass)
        return ClassSelectionSummary(
            inputClasses = inputClassCount,
            eligibleClasses = eligibleClassNames.size,
            matchedClasses = matchedClasses,
            skippedClasses = eligibleClassNames.size - matchedClasses,
            unmatchedKeepStringPackages = unmatchedSelectors(keepStringPackages, eligibleClassNames),
            unmatchedKeepMetadataPackages = unmatchedSelectors(keepMetadataPackages, eligibleClassNames),
        )
    }

    private fun isEligibleClass(internalClassName: String): Boolean = enabled && !isSupportClass(internalClassName)

    private fun isSupportClass(internalClassName: String): Boolean = internalClassName.startsWith("io/github/weg2022/strguard/")

    private fun matchesIncludedPackages(internalClassName: String, packageNames: List<String>): Boolean = packageNames.isEmpty() || matchesAnyPackage(internalClassName, packageNames)

    private fun matchesAnyPackage(internalClassName: String, packageNames: List<String>): Boolean = packageNames.any { packageName -> matchesPackage(internalClassName, packageName) }

    private fun validateExplicitIncludes(
        propertyName: String,
        selectors: List<String>,
        eligibleClassNames: List<String>,
    ) {
        selectors.forEach { selector ->
            if (eligibleClassNames.none { className -> matchesPackage(className, selector) }) {
                throw IllegalArgumentException(
                    "StrGuard $propertyName selector '$selector' did not match any eligible class",
                )
            }
        }
    }

    private fun unmatchedSelectors(
        selectors: List<String>,
        eligibleClassNames: List<String>,
    ): List<String> = selectors.filter { selector ->
        eligibleClassNames.none { className -> matchesPackage(className, selector) }
    }
}

internal data class ClassSelectionSummary(
    val inputClasses: Int,
    val eligibleClasses: Int,
    val matchedClasses: Int,
    val skippedClasses: Int,
    val unmatchedKeepStringPackages: List<String>,
    val unmatchedKeepMetadataPackages: List<String>,
) {
    fun warningMessages(): List<String> = unmatchedKeepStringPackages.map { selector ->
        "StrGuard keepStringPackages selector '$selector' did not match any eligible class"
    } +
        unmatchedKeepMetadataPackages.map { selector ->
            "StrGuard keepMetadataPackages selector '$selector' did not match any eligible class"
        }
}

internal data class TransformReport(
    val enabled: Boolean,
    val strictStringCoverage: Boolean,
    val runtimeTarget: String,
    val selection: ClassSelectionSummary,
    val stringCoverage: StringCoverage,
    val removedMetadata: Int,
) {
    fun asPropertiesText(): String = buildString {
        appendLine("schemaVersion=1")
        appendLine("enabled=$enabled")
        appendLine("strictStringCoverage=$strictStringCoverage")
        appendLine("runtimeTarget=$runtimeTarget")
        appendLine("inputClasses=${selection.inputClasses}")
        appendLine("eligibleClasses=${selection.eligibleClasses}")
        appendLine("matchedClasses=${selection.matchedClasses}")
        appendLine("skippedClasses=${selection.skippedClasses}")
        appendLine("stringCandidates=${stringCoverage.encounteredStrings}")
        appendLine("protectedStrings=${stringCoverage.protectedStrings}")
        appendLine("skippedStrings=${stringCoverage.skippedStrings}")
        appendLine("strictViolations=${stringCoverage.strictViolations}")
        appendLine("coverageUnknowns=${stringCoverage.coverageUnknowns}")
        StringSkipReason.entries.forEach { reason ->
            appendLine("${reason.reportProperty}=${stringCoverage.skipped(reason)}")
        }
        appendLine("removedMetadata=$removedMetadata")
        appendLine(
            "unmatchedKeepStringPackages=${selection.unmatchedKeepStringPackages.joinToString(",")}",
        )
        appendLine(
            "unmatchedKeepMetadataPackages=${selection.unmatchedKeepMetadataPackages.joinToString(",")}",
        )
    }
}

internal fun normalizePackageSelectors(propertyName: String, selectors: List<String>): List<String> = selectors
    .map { selector -> normalizePackageSelector(propertyName, selector) }
    .distinct()
    .sorted()

private fun normalizePackageSelector(propertyName: String, selector: String): String {
    val normalized = selector.trim().trim('/').replace('.', '/')
    if (normalized.isEmpty() || normalized.split('/').any { segment -> !isLegalPackageSegment(segment) }) {
        throw IllegalArgumentException(
            "StrGuard $propertyName selector '$selector' must contain only legal package segments",
        )
    }
    return normalized
}

private fun isLegalPackageSegment(segment: String): Boolean = segment.isNotEmpty() &&
    Character.isJavaIdentifierStart(segment.first()) &&
    segment.drop(1).all(Character::isJavaIdentifierPart)

private fun matchesPackage(internalClassName: String, packageName: String): Boolean {
    val classPackage = internalClassName.substringBeforeLast('/', missingDelimiterValue = "")
    return classPackage == packageName || classPackage.startsWith("$packageName/")
}
