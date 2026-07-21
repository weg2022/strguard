package io.github.weg2022.strguard

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class StrGuardExtension @Inject constructor(objects: ObjectFactory) {
    /** Disables all StrGuard class rewriting, metadata removal, and Native runtime generation. */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /** 256-bit release seed encoded as exactly 64 hexadecimal characters. */
    val releaseSeedHex: Property<String> = objects.property(String::class.java)

    /** Rust target triple for the generated desktop Native runtime. */
    val targetTriple: Property<String> = objects.property(String::class.java)

    /** Android ABIs generated for every enabled variant. */
    val androidAbis: SetProperty<String> =
        objects.setProperty(String::class.java).convention(AndroidAbi.entries.map(AndroidAbi::abiName).toSet())

    val java9StringConcatEnabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val consoleOutput: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    val removeMetadata: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Empty means every non-StrGuard application package is eligible. Entries use legal package segments. */
    val stringGuardPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Package selectors excluded from string protection. Entries use legal package segments. */
    val keepStringPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Empty means metadata is removed from every eligible class when enabled. Entries use legal package segments. */
    val removeMetadataPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Package selectors excluded from metadata removal. Entries use legal package segments. */
    val keepMetadataPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())
}

internal fun ListProperty<String>.normalizedPackageSelectors(propertyName: String): Provider<List<String>> = map { selectors -> normalizePackageSelectors(propertyName, selectors) }
