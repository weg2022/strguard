package io.github.weg2022.strguard

import java.util.*

internal enum class AndroidAbi(
    val abiName: String,
    val taskSuffix: String,
    val rustTriple: String,
    val ndkClangStem: String,
    val cargoLinkerEnvKey: String,
    val cargoArchiverEnvKey: String,
    val packagingDirectory: String,
) {
    ARMEABI_V7A(
        abiName = "armeabi-v7a",
        taskSuffix = "ArmeabiV7a",
        rustTriple = "armv7-linux-androideabi",
        ndkClangStem = "armv7a-linux-androideabi",
        cargoLinkerEnvKey = "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER",
        cargoArchiverEnvKey = "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR",
        packagingDirectory = "armeabi-v7a",
    ),
    ARM64_V8A(
        abiName = "arm64-v8a",
        taskSuffix = "Arm64V8a",
        rustTriple = "aarch64-linux-android",
        ndkClangStem = "aarch64-linux-android",
        cargoLinkerEnvKey = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
        cargoArchiverEnvKey = "CARGO_TARGET_AARCH64_LINUX_ANDROID_AR",
        packagingDirectory = "arm64-v8a",
    ),
    X86(
        abiName = "x86",
        taskSuffix = "X86",
        rustTriple = "i686-linux-android",
        ndkClangStem = "i686-linux-android",
        cargoLinkerEnvKey = "CARGO_TARGET_I686_LINUX_ANDROID_LINKER",
        cargoArchiverEnvKey = "CARGO_TARGET_I686_LINUX_ANDROID_AR",
        packagingDirectory = "x86",
    ),
    X86_64(
        abiName = "x86_64",
        taskSuffix = "X8664",
        rustTriple = "x86_64-linux-android",
        ndkClangStem = "x86_64-linux-android",
        cargoLinkerEnvKey = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
        cargoArchiverEnvKey = "CARGO_TARGET_X86_64_LINUX_ANDROID_AR",
        packagingDirectory = "x86_64",
    ),
    ;

    val libraryExtension: String = ".so"
    val cargoLibraryFileName: String = "libstrguard_native.so"

    fun packagedLibraryFileName(suffix: String): String = "libsg_$suffix$libraryExtension"

    fun packagedResourcePath(fileName: String): String = RuntimeFamily.ANDROID.packagedResourcePath(packagingDirectory, fileName)

    fun libraryLoadName(suffix: String): String = "sg_$suffix"

    val cargoToolEnvironmentSuffix: String
        get() = rustTriple.replace('-', '_')

    companion object {
        fun fromAbiName(value: String): AndroidAbi {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.singleOrNull { it.abiName == normalized }
                ?: throw IllegalArgumentException(
                    "Unsupported Android ABI '$value'. Supported ABIs: " +
                        entries.joinToString { it.abiName },
                )
        }

        fun fromRustTriple(value: String): AndroidAbi {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.singleOrNull { it.rustTriple == normalized }
                ?: throw IllegalArgumentException(
                    "Unsupported StrGuard Android Rust target '$value'. Supported targets: " +
                        entries.joinToString { it.rustTriple },
                )
        }
    }
}
