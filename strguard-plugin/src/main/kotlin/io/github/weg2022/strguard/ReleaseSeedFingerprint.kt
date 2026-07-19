package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun Project.releaseSeedFingerprint(extension: StrGuardExtension): Provider<String> =
    extension.enabled.flatMap { enabled ->
        if (enabled) {
            extension.releaseSeedHex
                .map { seed ->
                    CryptoPrimitives.hex(
                        CryptoPrimitives.sha256(CryptoPrimitives.parseHex256(seed)),
                    )
                }
                .orElse("missing")
        } else {
            providers.provider { "disabled" }
        }
    }
