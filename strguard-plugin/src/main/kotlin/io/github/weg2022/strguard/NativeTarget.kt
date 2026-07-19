package io.github.weg2022.strguard

import java.util.*

internal enum class NativeTarget(
    val rustTriple: String,
    val resourceDirectory: String,
    private val libraryPrefix: String,
    val libraryExtension: String,
    val cargoLibraryFileName: String,
    val extractFromResources: Boolean,
) {
    WINDOWS_X64(
        rustTriple = "x86_64-pc-windows-msvc",
        resourceDirectory = "windows-x86_64",
        libraryPrefix = "",
        libraryExtension = ".dll",
        cargoLibraryFileName = "strguard_native.dll",
        extractFromResources = true,
    ),
    LINUX_X64(
        rustTriple = "x86_64-unknown-linux-gnu",
        resourceDirectory = "linux-x86_64",
        libraryPrefix = "lib",
        libraryExtension = ".so",
        cargoLibraryFileName = "libstrguard_native.so",
        extractFromResources = true,
    ),
    MACOS_X64(
        rustTriple = "x86_64-apple-darwin",
        resourceDirectory = "macos-x86_64",
        libraryPrefix = "lib",
        libraryExtension = ".dylib",
        cargoLibraryFileName = "libstrguard_native.dylib",
        extractFromResources = true,
    ),
    MACOS_ARM64(
        rustTriple = "aarch64-apple-darwin",
        resourceDirectory = "macos-arm64",
        libraryPrefix = "lib",
        libraryExtension = ".dylib",
        cargoLibraryFileName = "libstrguard_native.dylib",
        extractFromResources = true,
    ),
    ANDROID_ARM64(
        rustTriple = "aarch64-linux-android",
        resourceDirectory = "arm64-v8a",
        libraryPrefix = "lib",
        libraryExtension = ".so",
        cargoLibraryFileName = "libstrguard_native.so",
        extractFromResources = false,
    ),
    ;

    fun packagedLibraryFileName(suffix: String): String =
        "${libraryPrefix}sg_$suffix$libraryExtension"

    fun packagedResourcePath(fileName: String): String =
        if (extractFromResources) {
            "META-INF/strguard/native/$resourceDirectory/$fileName"
        } else {
            "$resourceDirectory/$fileName"
        }

    fun libraryLoadName(suffix: String): String = "sg_$suffix"

    companion object {
        fun fromRustTriple(value: String): NativeTarget {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.singleOrNull { it.rustTriple == normalized }
                ?: throw IllegalArgumentException(
                    "Unsupported StrGuard Native target '$value'. Supported targets: " +
                            entries.joinToString { it.rustTriple },
                )
        }

        fun detectHost(osName: String, architecture: String): NativeTarget {
            val os = osName.trim().lowercase(Locale.ROOT)
            val arch = architecture.trim().lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") && arch in X64_ARCHITECTURES -> WINDOWS_X64
                os.startsWith("linux") && arch in X64_ARCHITECTURES -> LINUX_X64
                (os.startsWith("mac") || os.startsWith("darwin")) && arch in X64_ARCHITECTURES -> MACOS_X64
                (os.startsWith("mac") || os.startsWith("darwin")) && arch in ARM64_ARCHITECTURES -> MACOS_ARM64
                else -> throw IllegalArgumentException(
                    "Unsupported StrGuard host platform os.name='$osName', os.arch='$architecture'",
                )
            }
        }
    }
}

private val X64_ARCHITECTURES = setOf("amd64", "x86_64", "x86-64")
private val ARM64_ARCHITECTURES = setOf("aarch64", "arm64")
