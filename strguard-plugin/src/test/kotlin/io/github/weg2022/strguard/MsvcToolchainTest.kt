package io.github.weg2022.strguard

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MsvcToolchainTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @ParameterizedTest
    @MethodSource("visualStudioInstallations")
    fun `discovers MSVC toolsets across Visual Studio release directory formats`(
        releaseDirectory: String,
        targetTriple: String,
        targetArchitecture: String,
        hostArchitecture: String,
    ) {
        val programFiles = temporaryDirectory.resolve("Program Files $releaseDirectory $targetArchitecture")
        val tools =
            programFiles.resolve(
                "Microsoft Visual Studio/$releaseDirectory/Enterprise/VC/Tools/MSVC/14.50.35717",
            )
        val linker = tools.resolve("bin/$hostArchitecture/$targetArchitecture/link.exe")
        Files.createDirectories(linker.parent)
        Files.createFile(linker)

        val msvcLibrary = tools.resolve("lib/$targetArchitecture")
        Files.createDirectories(msvcLibrary)
        val windowsSdk = programFiles.resolve("Windows Kits/10/Lib/10.0.26100.0")
        val ucrtLibrary = windowsSdk.resolve("ucrt/$targetArchitecture")
        val umLibrary = windowsSdk.resolve("um/$targetArchitecture")
        Files.createDirectories(ucrtLibrary)
        Files.createDirectories(umLibrary)

        val toolchain =
            assertNotNull(
                findMsvcToolchain(
                    JvmNativeTarget.fromRustTriple(targetTriple),
                    mapOf("ProgramFiles" to programFiles.toString()),
                ),
            )

        assertEquals(linker, toolchain.linker)
        assertEquals(listOf(msvcLibrary, ucrtLibrary, umLibrary).joinToString(";"), toolchain.libraryPath)
    }

    companion object {
        @JvmStatic
        fun visualStudioInstallations(): List<Arguments> = listOf(
            Arguments.of("2022", "x86_64-pc-windows-msvc", "x64", "Hostx64"),
            Arguments.of("18", "x86_64-pc-windows-msvc", "x64", "Hostx64"),
            Arguments.of("18", "aarch64-pc-windows-msvc", "arm64", "Hostarm64"),
        )
    }
}
