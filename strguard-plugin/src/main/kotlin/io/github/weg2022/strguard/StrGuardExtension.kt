package io.github.weg2022.strguard

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class StrGuardExtension @Inject constructor(objects: ObjectFactory) {
    /** Disables all StrGuard class rewriting, metadata removal, and Native runtime generation. */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /** 256-bit release seed encoded as exactly 64 hexadecimal characters. */
    val releaseSeedHex: Property<String> = objects.property(String::class.java)

    /** Rust target triple for the generated desktop Native runtime. */
    val targetTriple: Property<String> = objects.property(String::class.java)

    val java9StringConcatEnabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val consoleOutput: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    val removeMetadata: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Empty means every non-StrGuard application package is eligible. */
    val stringGuardPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    val keepStringPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Empty means metadata is removed from every eligible class when enabled. */
    val removeMetadataPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    val keepMetadataPackages: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())
}
