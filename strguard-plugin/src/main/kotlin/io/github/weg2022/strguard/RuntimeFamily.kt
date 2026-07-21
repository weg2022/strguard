package io.github.weg2022.strguard

internal enum class RuntimeFamily(
    val extractFromResources: Boolean,
) {
    JVM(extractFromResources = true),
    ANDROID(extractFromResources = false),
    ;

    fun packagedResourcePath(packagingDirectory: String, fileName: String): String = when (this) {
        JVM -> "META-INF/strguard/native/$packagingDirectory/$fileName"
        ANDROID -> "$packagingDirectory/$fileName"
    }
}
