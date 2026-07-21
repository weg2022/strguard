package io.github.weg2022.strguard

import io.github.weg2022.strguard.vault.GATEWAY_COUNT
import org.gradle.api.GradleException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

internal data class StrGuardArtifactMetadata(
    val artifactId: String,
    val stage: String,
    val runtimeFamily: String,
    val runtimeTarget: String,
    val bridgeClass: String,
    val loaderClass: String,
    val gatewayNames: List<String>,
    val nativeResources: Map<String, String>,
    val vaultRecords: Int,
    val androidAbis: List<String> = emptyList(),
    val minSdk: Int? = null,
    val shrinkerId: String = "",
) {
    val markerPath: String
        get() = "$STRGUARD_ARTIFACT_MARKER_DIRECTORY/$artifactId.properties"

    val embeddedRulesPath: String
        get() = "$STRGUARD_EMBEDDED_RULES_DIRECTORY/strguard-$artifactId.pro"

    fun withShrunkStage(shrinkerId: String): StrGuardArtifactMetadata = copy(stage = STRGUARD_ARTIFACT_STAGE_SHRUNK, shrinkerId = shrinkerId)

    fun asPropertiesText(): String = buildString {
        appendLine("schemaVersion=1")
        appendLine("artifactId=$artifactId")
        appendLine("stage=$stage")
        appendLine("runtimeFamily=$runtimeFamily")
        appendLine("runtimeTarget=$runtimeTarget")
        appendLine("bridgeClass=$bridgeClass")
        appendLine("loaderClass=$loaderClass")
        appendLine("gatewayNames=${gatewayNames.joinToString(",")}")
        appendLine(
            "nativeResources=" +
                nativeResources.toSortedMap().entries.joinToString(",") { (path, hash) -> "$path:$hash" },
        )
        appendLine("vaultRecords=$vaultRecords")
        appendLine("androidAbis=${androidAbis.sorted().joinToString(",")}")
        appendLine("minSdk=${minSdk ?: ""}")
        appendLine("shrinkerId=$shrinkerId")
    }

    companion object {
        fun fromRuntimeProperties(
            properties: Properties,
            runtimeTarget: String,
            nativeResources: Map<String, String>,
            androidAbis: List<String> = emptyList(),
            minSdk: Int? = null,
        ): StrGuardArtifactMetadata = StrGuardArtifactMetadata(
            artifactId = properties.required("artifactId"),
            stage = STRGUARD_ARTIFACT_STAGE_PROTECTED,
            runtimeFamily = properties.required("runtimeFamily"),
            runtimeTarget = runtimeTarget,
            bridgeClass = properties.required("bridgeClass"),
            loaderClass = properties.getProperty("loaderClass").orEmpty(),
            gatewayNames = properties.required("gatewayNames").split(',').filter(String::isNotEmpty),
            nativeResources = nativeResources,
            vaultRecords = properties.required("vaultRecords").toIntOrNull()
                ?: throw GradleException("Invalid StrGuard vaultRecords metadata"),
            androidAbis = androidAbis.sorted(),
            minSdk = minSdk,
        ).also(StrGuardArtifactMetadata::validate)

        fun parse(input: InputStream): StrGuardArtifactMetadata {
            val properties = Properties().apply { load(input) }
            if (properties.required("schemaVersion") != "1") {
                throw GradleException("Unsupported StrGuard artifact metadata schema")
            }
            val nativeResources =
                properties.required("nativeResources")
                    .split(',')
                    .filter(String::isNotEmpty)
                    .associate { entry ->
                        val separator = entry.lastIndexOf(':')
                        if (separator <= 0 || separator == entry.lastIndex) {
                            throw GradleException("Invalid StrGuard Native resource metadata")
                        }
                        entry.substring(0, separator) to entry.substring(separator + 1)
                    }
            return StrGuardArtifactMetadata(
                artifactId = properties.required("artifactId"),
                stage = properties.required("stage"),
                runtimeFamily = properties.required("runtimeFamily"),
                runtimeTarget = properties.required("runtimeTarget"),
                bridgeClass = properties.required("bridgeClass"),
                loaderClass = properties.getProperty("loaderClass").orEmpty(),
                gatewayNames = properties.required("gatewayNames").split(',').filter(String::isNotEmpty),
                nativeResources = nativeResources,
                vaultRecords = properties.required("vaultRecords").toIntOrNull()
                    ?: throw GradleException("Invalid StrGuard vaultRecords metadata"),
                androidAbis =
                properties.getProperty("androidAbis").orEmpty().split(',')
                    .filter(String::isNotEmpty)
                    .sorted(),
                minSdk = properties.getProperty("minSdk")?.takeIf(String::isNotBlank)?.toIntOrNull(),
                shrinkerId = properties.getProperty("shrinkerId").orEmpty(),
            ).also(StrGuardArtifactMetadata::validate)
        }
    }

    private fun validate() {
        if (gatewayNames.size != GATEWAY_COUNT) {
            throw GradleException("StrGuard artifact metadata must contain $GATEWAY_COUNT gateway names")
        }
        if (runtimeFamily == "android") {
            if (androidAbis.isEmpty()) {
                throw GradleException("StrGuard Android artifact metadata must declare androidAbis")
            }
            if (minSdk == null || minSdk < 21) {
                throw GradleException("StrGuard Android artifact metadata must declare minSdk >= 21")
            }
        }
    }
}

internal object StrGuardShrinkerRules {
    val text: String =
        """
        # Class and member names are part of the Rust FindClass/RegisterNatives ABI.
        -keep class io.github.weg2022.strguard.generated.B* { *; }
        -keep class io.github.weg2022.strguard.generated.L* { *; }
        """.trimIndent() + "\n"
}

internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun writeArtifactMetadata(outputRoot: Path, metadata: StrGuardArtifactMetadata) {
    val marker = outputRoot.resolve(metadata.markerPath)
    Files.createDirectories(marker.parent)
    Files.writeString(marker, metadata.asPropertiesText(), StandardCharsets.UTF_8)
    val rules = outputRoot.resolve(metadata.embeddedRulesPath)
    Files.createDirectories(rules.parent)
    Files.writeString(rules, StrGuardShrinkerRules.text, StandardCharsets.UTF_8)
}

private fun Properties.required(name: String): String = getProperty(name)?.takeIf(String::isNotBlank)
    ?: throw GradleException("StrGuard artifact metadata has no $name")

internal const val STRGUARD_ARTIFACT_MARKER_DIRECTORY = "META-INF/strguard/artifacts"
internal const val STRGUARD_EMBEDDED_RULES_DIRECTORY = "META-INF/proguard"
internal const val STRGUARD_ARTIFACT_STAGE_PROTECTED = "protected"
internal const val STRGUARD_ARTIFACT_STAGE_SHRUNK = "shrunk"
