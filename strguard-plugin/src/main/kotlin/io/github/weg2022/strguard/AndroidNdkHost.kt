package io.github.weg2022.strguard

import java.util.*

internal enum class AndroidNdkHost(
    val tag: String,
    val clangCommandSuffix: String,
    val executableSuffix: String,
) {
    WINDOWS(
        tag = "windows-x86_64",
        clangCommandSuffix = ".cmd",
        executableSuffix = ".exe",
    ),
    LINUX(
        tag = "linux-x86_64",
        clangCommandSuffix = "",
        executableSuffix = "",
    ),
    MACOS(
        tag = "darwin-x86_64",
        clangCommandSuffix = "",
        executableSuffix = "",
    ),
    ;

    fun clangExecutableName(abi: AndroidAbi, minSdk: Int): String = "${abi.ndkClangStem}$minSdk-clang$clangCommandSuffix"

    fun executableName(stem: String): String = "$stem$executableSuffix"

    companion object {
        fun fromTag(value: String): AndroidNdkHost = entries.singleOrNull { it.tag == value }
            ?: throw IllegalArgumentException("Unsupported Android NDK host tag '$value'")

        fun detect(osName: String, architecture: String): AndroidNdkHost {
            val os = osName.trim().lowercase(Locale.ROOT)
            val arch = architecture.trim().lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") && arch in NDK_HOST_ARCHITECTURES -> WINDOWS

                os.startsWith("linux") && arch in NDK_HOST_ARCHITECTURES -> LINUX

                (os.startsWith("mac") || os.startsWith("darwin")) && arch in NDK_HOST_ARCHITECTURES -> MACOS

                else -> throw IllegalArgumentException(
                    "Unsupported Android NDK host os.name='$osName', os.arch='$architecture'",
                )
            }
        }
    }
}

// ARM64 host aliases deliberately resolve to the NDK's published x86_64 prebuilt tags.
private val NDK_HOST_ARCHITECTURES =
    setOf("amd64", "x86_64", "x86-64", "x64", "aarch64", "arm64")
