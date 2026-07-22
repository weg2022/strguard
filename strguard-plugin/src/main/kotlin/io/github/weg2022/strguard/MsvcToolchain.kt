package io.github.weg2022.strguard

import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.Path

internal data class MsvcToolchain(
    val linker: Path,
    val libraryPath: String,
)

internal fun findMsvcToolchain(
    target: JvmNativeTarget,
    environment: Map<String, String> = System.getenv(),
): MsvcToolchain? {
    val targetArchitecture =
        when (target) {
            JvmNativeTarget.WINDOWS_X64 -> "x64"
            JvmNativeTarget.WINDOWS_ARM64 -> "arm64"
            else -> return null
        }
    val configuredTools = environment["VCToolsInstallDir"]?.let(Path::of)
    configuredTools?.let { tools ->
        findHostLinker(tools, targetArchitecture)?.let { linker ->
            return MsvcToolchain(linker, msvcLibraryPath(tools, targetArchitecture, environment))
        }
    }

    val installationRoots =
        listOfNotNull(environment["ProgramFiles"], environment["ProgramFiles(x86)"])
            .distinct()
            .map(Path::of)
            .flatMap(::visualStudioInstallations)
    val toolsets =
        installationRoots.flatMap { installation ->
            val msvc = installation.resolve("VC/Tools/MSVC")
            if (!Files.isDirectory(msvc)) {
                emptyList()
            } else {
                Files.list(msvc).use { versions -> versions.iterator().asSequence().toList() }
            }
        }.sortedWith(compareByDescending<Path> { it.fileName.toString() }.thenBy { it.toString() })
    val tools =
        toolsets.firstOrNull { candidate -> findHostLinker(candidate, targetArchitecture) != null }
            ?: throw GradleException(
                "StrGuard cannot find a Visual Studio MSVC linker for ${target.rustTriple}",
            )
    return MsvcToolchain(
        linker = requireNotNull(findHostLinker(tools, targetArchitecture)),
        libraryPath = msvcLibraryPath(tools, targetArchitecture, environment),
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

private fun visualStudioInstallations(programFiles: Path): List<Path> = childDirectories(programFiles.resolve("Microsoft Visual Studio"))
    .flatMap { release -> listOf(release) + childDirectories(release) }

private fun childDirectories(directory: Path): List<Path> = if (Files.isDirectory(directory)) {
    Files.list(directory).use { children ->
        children.iterator().asSequence().filter(Files::isDirectory).toList()
    }
} else {
    emptyList()
}

private fun msvcLibraryPath(
    tools: Path,
    targetArchitecture: String,
    environment: Map<String, String>,
): String {
    val msvcLibrary = tools.resolve("lib/$targetArchitecture")
    if (!Files.isDirectory(msvcLibrary)) {
        throw GradleException("StrGuard cannot find MSVC libraries under $msvcLibrary")
    }
    val windowsKitsRoot =
        listOfNotNull(environment["ProgramFiles(x86)"], environment["ProgramFiles"])
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
