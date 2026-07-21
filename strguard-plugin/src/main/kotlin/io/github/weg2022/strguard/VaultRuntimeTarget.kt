package io.github.weg2022.strguard

internal interface VaultRuntimeTarget {
    val vaultIdentity: String
    val runtimeFamily: RuntimeFamily

    fun packagedLibraryFileName(suffix: String): String

    fun packagedResourcePath(fileName: String): String

    fun libraryLoadName(suffix: String): String
}

internal object AndroidVaultTarget : VaultRuntimeTarget {
    override val vaultIdentity: String = "android"
    override val runtimeFamily: RuntimeFamily = RuntimeFamily.ANDROID

    override fun packagedLibraryFileName(suffix: String): String = "libsg_$suffix.so"

    override fun packagedResourcePath(fileName: String): String = fileName

    override fun libraryLoadName(suffix: String): String = "sg_$suffix"
}
