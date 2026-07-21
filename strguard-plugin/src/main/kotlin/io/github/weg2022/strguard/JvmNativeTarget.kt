package io.github.weg2022.strguard

import java.util.*

internal enum class JvmNativeTarget(
    val rustTriple: String,
    val packagingDirectory: String,
    private val libraryPrefix: String,
    val libraryExtension: String,
    val cargoLibraryFileName: String,
) : VaultRuntimeTarget {
    WINDOWS_X64(
        rustTriple = "x86_64-pc-windows-msvc",
        packagingDirectory = "windows-x86_64",
        libraryPrefix = "",
        libraryExtension = ".dll",
        cargoLibraryFileName = "strguard_native.dll",
    ),
    WINDOWS_ARM64(
        rustTriple = "aarch64-pc-windows-msvc",
        packagingDirectory = "windows-arm64",
        libraryPrefix = "",
        libraryExtension = ".dll",
        cargoLibraryFileName = "strguard_native.dll",
    ),
    LINUX_GLIBC_X64(
        rustTriple = "x86_64-unknown-linux-gnu",
        packagingDirectory = "linux-x86_64",
        libraryPrefix = "lib",
        libraryExtension = ".so",
        cargoLibraryFileName = "libstrguard_native.so",
    ),
    LINUX_GLIBC_ARM64(
        rustTriple = "aarch64-unknown-linux-gnu",
        packagingDirectory = "linux-arm64",
        libraryPrefix = "lib",
        libraryExtension = ".so",
        cargoLibraryFileName = "libstrguard_native.so",
    ),
    MACOS_X64(
        rustTriple = "x86_64-apple-darwin",
        packagingDirectory = "macos-x86_64",
        libraryPrefix = "lib",
        libraryExtension = ".dylib",
        cargoLibraryFileName = "libstrguard_native.dylib",
    ),
    MACOS_ARM64(
        rustTriple = "aarch64-apple-darwin",
        packagingDirectory = "macos-arm64",
        libraryPrefix = "lib",
        libraryExtension = ".dylib",
        cargoLibraryFileName = "libstrguard_native.dylib",
    ),
    ;

    override val vaultIdentity: String
        get() = rustTriple

    override val runtimeFamily: RuntimeFamily
        get() = RuntimeFamily.JVM

    override fun packagedLibraryFileName(suffix: String): String = "${libraryPrefix}sg_$suffix$libraryExtension"

    override fun packagedResourcePath(fileName: String): String = RuntimeFamily.JVM.packagedResourcePath(packagingDirectory, fileName)

    override fun libraryLoadName(suffix: String): String = "sg_$suffix"

    companion object {
        fun fromRustTriple(value: String): JvmNativeTarget {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.singleOrNull { it.rustTriple == normalized }
                ?: throw IllegalArgumentException(
                    "Unsupported StrGuard JVM Native target '$value'. Supported targets: " +
                        entries.joinToString { it.rustTriple },
                )
        }

        fun detectHost(osName: String, architecture: String): JvmNativeTarget {
            val os = osName.trim().lowercase(Locale.ROOT)
            val arch = architecture.trim().lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") && arch in JVM_X64_ARCHITECTURES -> WINDOWS_X64

                os.startsWith("windows") && arch in JVM_ARM64_ARCHITECTURES -> WINDOWS_ARM64

                os.startsWith("linux") && arch in JVM_X64_ARCHITECTURES -> LINUX_GLIBC_X64

                os.startsWith("linux") && arch in JVM_ARM64_ARCHITECTURES -> LINUX_GLIBC_ARM64

                (os.startsWith("mac") || os.startsWith("darwin")) && arch in JVM_X64_ARCHITECTURES -> MACOS_X64

                (os.startsWith("mac") || os.startsWith("darwin")) && arch in JVM_ARM64_ARCHITECTURES -> MACOS_ARM64

                else -> throw IllegalArgumentException(
                    "Unsupported StrGuard JVM host platform os.name='$osName', os.arch='$architecture'",
                )
            }
        }
    }
}

private val JVM_X64_ARCHITECTURES = setOf("amd64", "x86_64", "x86-64", "x64")
private val JVM_ARM64_ARCHITECTURES = setOf("aarch64", "arm64")
