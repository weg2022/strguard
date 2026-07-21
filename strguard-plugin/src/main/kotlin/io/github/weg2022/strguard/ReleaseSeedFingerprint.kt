package io.github.weg2022.strguard

import io.github.weg2022.strguard.crypto.CryptoPrimitives
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider

internal const val DISABLED_STRGUARD_VALUE = "disabled"

internal fun <T : Any> strGuardProvider(
    enabled: Provider<Boolean>,
    enabledValue: Provider<T>,
    disabledValue: Provider<T>,
): Provider<T> = enabled.flatMap { isEnabled ->
    if (isEnabled) enabledValue else disabledValue
}

internal fun <T : Any> Project.strGuardProvider(
    enabled: Provider<Boolean>,
    enabledValue: Provider<T>,
    disabledValue: T,
): Provider<T> = strGuardProvider(
    enabled = enabled,
    enabledValue = enabledValue,
    disabledValue = providers.provider { disabledValue },
)

internal fun Project.releaseSeed(extension: StrGuardExtension): Provider<String> = strGuardProvider(
    enabled = extension.enabled,
    enabledValue = extension.releaseSeedHex,
    disabledValue = DISABLED_STRGUARD_VALUE,
)

internal fun Project.releaseSeedFingerprint(extension: StrGuardExtension): Provider<String> = strGuardProvider(
    enabled = extension.enabled,
    enabledValue =
    extension.releaseSeedHex
        .map { seed ->
            CryptoPrimitives.hex(
                CryptoPrimitives.sha256(CryptoPrimitives.parseHex256(seed)),
            )
        }
        .orElse("missing"),
    disabledValue = DISABLED_STRGUARD_VALUE,
)

internal fun Project.strGuardPackageSelectors(
    extension: StrGuardExtension,
    selectors: ListProperty<String>,
    propertyName: String,
): Provider<List<String>> = strGuardProvider(
    enabled = extension.enabled,
    enabledValue = selectors.normalizedPackageSelectors(propertyName),
    disabledValue = emptyList(),
)
