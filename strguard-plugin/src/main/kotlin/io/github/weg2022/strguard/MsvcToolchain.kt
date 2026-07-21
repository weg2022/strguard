package io.github.weg2022.strguard

import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.Path

internal data class MsvcToolchain(
    val linker: Path,
    val libraryPath: String,
)

internal fun findMsvcToolchain(target: JvmNativeTarget): MsvcToolchain? {
    val targetArchitecture =
        when (target) {
            JvmNativeTarget.WINDOWS_X64 -> "x64"
            JvmNativeTarget.WINDOWS_ARM64 -> "arm64"
            else -> return null
        }
    val configuredTools = System.getenv("VCToolsInstallDir")?.let(Path::of)
    configuredTools?.let { tools ->
        findHostLinker(tools, targetArchitecture)?.let { linker ->
            return MsvcToolchain(linker, msvcLibraryPath(tools, targetArchitecture))
        }
    }

    val installationRoots =
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .distinct()
            .map(Path::of)
            .flatMap { programFiles ->
                val visualStudio = programFiles.resolve("Microsoft Visual Studio/2022")
                if (!Files.isDirectory(visualStudio)) {
                    emptyList()
                } else {
                    Files.list(visualStudio).use { editions -> editions.iterator().asSequence().toList() }
                }
            }
    val toolsets =
        installationRoots.flatMap { installation ->
            val msvc = installation.resolve("VC/Tools/MSVC")
            if (!Files.isDirectory(msvc)) {
                emptyList()
            } else {
                Files.list(msvc).use { versions -> versions.iterator().asSequence().toList() }
            }
        }.sortedByDescending { it.fileName.toString() }
    val tools =
        toolsets.firstOrNull { candidate -> findHostLinker(candidate, targetArchitecture) != null }
            ?: throw GradleException(
                "StrGuard cannot find the Visual Studio 2022 MSVC linker for ${target.rustTriple}",
            )
    return MsvcToolchain(
        linker = requireNotNull(findHostLinker(tools, targetArchitecture)),
        libraryPath = msvcLibraryPath(tools, targetArchitecture),
    )
}

internal fun cargoLinkerEnvironmentKey(target: JvmNativeTarget): String = "CARGO_TARGET_${target.rustTriple.uppercase().replace('-', '_')}_LINKER"

internal fun addHostMsvcEnvironment(environment: MutableMap<String, String>) {
    val host = JvmNativeTarget.detectHost(System.getProperty("os.name"), System.getProperty("os.arch"))
    findMsvcToolchain(host)?.let { toolchain ->
        environment[cargoLinkerEnvironmentKey(host)] = toolchain.linker.toString()
        environment["LIB"] = toolchain.libraryPath
    }
}

private fun findHostLinker(tools: Path, targetArchitecture: String): Path? = listOf("Hostx64", "Hostarm64").asSequence()
    .map { host -> tools.resolve("bin/$host/$targetArchitecture/link.exe") }
    .firstOrNull(Files::isRegularFile)

private fun msvcLibraryPath(tools: Path, targetArchitecture: String): String {
    val msvcLibrary = tools.resolve("lib/$targetArchitecture")
    if (!Files.isDirectory(msvcLibrary)) {
        throw GradleException("StrGuard cannot find MSVC libraries under $msvcLibrary")
    }
    val windowsKitsRoot =
        listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"))
            .asSequence()
            .map(Path::of)
            .map { programFiles -> programFiles.resolve("Windows Kits/10/Lib") }
            .firstOrNull(Files::isDirectory)
            ?: throw GradleException("StrGuard cannot find the Windows 10 SDK libraries")
    val sdkVersion =
        Files.list(windowsKitsRoot).use { versions ->
            versions.iterator().asSequence()
                .filter { version ->
                    Files.isDirectory(version.resolve("um/$targetArchitecture")) &&
                        Files.isDirectory(version.resolve("ucrt/$targetArchitecture"))
                }.maxByOrNull { version -> version.fileName.toString() }
        } ?: throw GradleException("StrGuard cannot find Windows SDK libraries for $targetArchitecture")
    return listOf(
        msvcLibrary,
        sdkVersion.resolve("ucrt/$targetArchitecture"),
        sdkVersion.resolve("um/$targetArchitecture"),
    ).joinToString(";")
}
