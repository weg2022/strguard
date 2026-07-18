package io.github.weg2022.strguard

internal data class TransformSettings(
    val enabled: Boolean,
    val java9StringConcatEnabled: Boolean,
    val removeMetadata: Boolean,
    val stringGuardPackages: List<String>,
    val keepStringPackages: List<String>,
    val removeMetadataPackages: List<String>,
    val keepMetadataPackages: List<String>,
) {
    fun shouldTransformClass(internalClassName: String): Boolean =
        shouldTransformStrings(internalClassName) || shouldRemoveMetadata(internalClassName)

    fun shouldTransformStrings(internalClassName: String): Boolean =
        enabled &&
            !isSupportClass(internalClassName) &&
            matchesIncludedPackages(internalClassName, stringGuardPackages) &&
            !matchesAnyPackage(internalClassName, keepStringPackages)

    fun shouldRemoveMetadata(internalClassName: String): Boolean =
        removeMetadata &&
            !isSupportClass(internalClassName) &&
            matchesIncludedPackages(internalClassName, removeMetadataPackages) &&
            !matchesAnyPackage(internalClassName, keepMetadataPackages)

    private fun isSupportClass(internalClassName: String): Boolean =
        internalClassName.startsWith("io/github/weg2022/strguard/")

    private fun matchesIncludedPackages(internalClassName: String, packageNames: List<String>): Boolean =
        packageNames.isEmpty() || matchesAnyPackage(internalClassName, packageNames)

    private fun matchesAnyPackage(internalClassName: String, packageNames: List<String>): Boolean =
        packageNames.any { packageName ->
            val normalizedName = packageName.trim().replace('.', '/').trim('/')
            normalizedName.isNotEmpty() &&
                (internalClassName == normalizedName || internalClassName.startsWith("$normalizedName/"))
        }
}
