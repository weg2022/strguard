package io.github.weg2022.strguard

internal class TransformSettings(
    val enabled: Boolean,
    val java9StringConcatEnabled: Boolean,
    val strictStringCoverage: Boolean = false,
    val removeSourceDebugExtension: Boolean,
    stringGuardPackages: List<String>,
    keepStringPackages: List<String>,
    removeSourceDebugExtensionPackages: List<String>,
    keepSourceDebugExtensionPackages: List<String>,
    val aiPolicyEnabled: Boolean = false,
    val aiPolicyContact: String? = null,
    aiPolicyExceptions: List<String> = emptyList(),
    aiPolicyPackages: List<String> = emptyList(),
    aiPolicyExcludePackages: List<String> = emptyList(),
    val moduleCoordinates: ModuleCoordinates? = null,
) {
    val stringGuardPackages: List<String> =
        normalizePackageSelectors("stringGuardPackages", stringGuardPackages)
    val keepStringPackages: List<String> =
        normalizePackageSelectors("keepStringPackages", keepStringPackages)
    val removeSourceDebugExtensionPackages: List<String> =
        normalizePackageSelectors("removeSourceDebugExtensionPackages", removeSourceDebugExtensionPackages)
    val keepSourceDebugExtensionPackages: List<String> =
        normalizePackageSelectors("keepSourceDebugExtensionPackages", keepSourceDebugExtensionPackages)
    val aiPolicyExceptions: List<String> =
        aiPolicyExceptions.map(String::trim).filter(String::isNotEmpty)
    val aiPolicyPackages: List<String> =
        normalizePackageSelectors("aiPolicyPackages", aiPolicyPackages)
    val aiPolicyExcludePackages: List<String> =
        normalizePackageSelectors("aiPolicyExcludePackages", aiPolicyExcludePackages)

    /**
     * AI 逆向禁止策略标记与字符串保护、debug 信息移除完全正交:即使字符串保护未选中
     * 某类,只要该类符合 aiPolicy 选择,也必须进入变换管线以写入 marker(见
     * TransformClassesTask 的 shouldTransformClass 门控)。include 空 = 全部 eligible
     * class;exclude 优先。
     */
    fun shouldApplyAiPolicy(internalClassName: String): Boolean = aiPolicyEnabled &&
        isEligibleClass(internalClassName) &&
        matchesIncludedPackages(internalClassName, aiPolicyPackages) &&
        !matchesAnyPackage(internalClassName, aiPolicyExcludePackages)

    fun shouldTransformClass(internalClassName: String): Boolean = shouldTransformStrings(internalClassName) ||
        shouldRemoveSourceDebugExtension(internalClassName) ||
        shouldApplyAiPolicy(internalClassName)

    fun shouldTransformStrings(internalClassName: String): Boolean = isEligibleClass(internalClassName) &&
        matchesIncludedPackages(internalClassName, stringGuardPackages) &&
        !matchesAnyPackage(internalClassName, keepStringPackages)

    fun shouldRemoveSourceDebugExtension(internalClassName: String): Boolean = isEligibleClass(internalClassName) &&
        removeSourceDebugExtension &&
        matchesIncludedPackages(internalClassName, removeSourceDebugExtensionPackages) &&
        !matchesAnyPackage(internalClassName, keepSourceDebugExtensionPackages)

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
                unmatchedKeepSourceDebugExtensions = emptyList(),
            )
        }

        val eligibleClassNames = internalClassNames.filter(::isEligibleClass)
        validateExplicitIncludes("stringGuardPackages", stringGuardPackages, eligibleClassNames)
        if (removeSourceDebugExtension) {
            validateExplicitIncludes(
                "removeSourceDebugExtensionPackages",
                removeSourceDebugExtensionPackages,
                eligibleClassNames,
            )
        }
        if (aiPolicyEnabled) {
            validateExplicitIncludes("aiPolicyPackages", aiPolicyPackages, eligibleClassNames)
            validateExplicitIncludes("aiPolicyExcludePackages", aiPolicyExcludePackages, eligibleClassNames)
        }
        val matchedClasses = eligibleClassNames.count(::shouldTransformClass)
        return ClassSelectionSummary(
            inputClasses = inputClassCount,
            eligibleClasses = eligibleClassNames.size,
            matchedClasses = matchedClasses,
            skippedClasses = eligibleClassNames.size - matchedClasses,
            unmatchedKeepStringPackages = unmatchedSelectors(keepStringPackages, eligibleClassNames),
            unmatchedKeepSourceDebugExtensions = unmatchedSelectors(keepSourceDebugExtensionPackages, eligibleClassNames),
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
    val unmatchedKeepSourceDebugExtensions: List<String>,
) {
    fun warningMessages(): List<String> = unmatchedKeepStringPackages.map { selector ->
        "StrGuard keepStringPackages selector '$selector' did not match any eligible class"
    } +
        unmatchedKeepSourceDebugExtensions.map { selector ->
            "StrGuard keepSourceDebugExtensionPackages selector '$selector' did not match any eligible class"
        }
}

internal data class TransformReport(
    val enabled: Boolean,
    val strictStringCoverage: Boolean,
    val runtimeTarget: String,
    val selection: ClassSelectionSummary,
    val stringCoverage: StringCoverage,
    val removedSourceDebugExtensions: Int,
) {
    fun asPropertiesText(): String = buildString {
        appendLine("schemaVersion=2")
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
        appendLine("removedSourceDebugExtensions=$removedSourceDebugExtensions")
        appendLine(
            "unmatchedKeepStringPackages=${selection.unmatchedKeepStringPackages.joinToString(",")}",
        )
        appendLine(
            "unmatchedKeepSourceDebugExtensions=${selection.unmatchedKeepSourceDebugExtensions.joinToString(",")}",
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
