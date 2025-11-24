# StrGuard

StrGuard is a Gradle plugin inspired by [StringFog](https://github.com/MegatronKing/StringFog). It automatically encrypts string constants in Java and Kotlin projects at compile time, adding a layer of protection to make reverse engineering harder and to safeguard sensitive information in your code.

## Features

* Works with Java and Kotlin projects
* Supports custom encryption/decryption algorithms
* Optional string encryption and metadata removal
* Fully automated Gradle integration
* Customizable key generators
* Selective package protection or exclusion
* Annotation-based exclusion for specific classes
* Removes Kotlin metadata and other debug information if desired
* Generates mapping files to aid debugging

## Installation

You can apply it in your root `build.gradle` or `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.weg2022.strguard") version "1.0.0"
}
```

Or using the legacy buildscript approach:

```kotlin
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.github.weg2022:strguard-plugin:1.0.0")
    }
}

apply(plugin = "io.github.weg2022.strguard")
```

## Configuration

Configure StrGuard in the `build.gradle` or `build.gradle.kts` of your app or library module:

```kotlin
strGuard {
    // Enable or disable string encryption (default: true)
    stringGuard = true
    
    // Enable Java 9+ string concatenation support
    v9StringConcatEnabled = true
    
    // Generate mapping files for debugging
    generateMappings = false
    
    // Print logs to console
    consoleOutput = false
    
    // Remove metadata from compiled classes
    removeMetadata = false
    
    // Packages to encrypt (whitelist)
    stringGuardPackages = ["com.example.myapp"]
    
    // Packages to exclude from encryption (blacklist)
    keepStringPackages = []
    
    // Packages to keep metadata
    keepMetadataPackages = []
    
    // Packages to remove metadata
    removeMetadataPackages = []
    
    // Custom key generator (default: HardCodeKeyGenerator("StrGuard"))
    keyGenerator = io.github.weg2022.strguard.api.HardCodeKeyGenerator("MySecretKey")
    // Or use a random key generator
    // keyGenerator = io.github.weg2022.strguard.api.RandomKeyGenerator(8)
}
```

## Notes

* Certain system classes and special strings (e.g., class names, method descriptors) are automatically skipped
* Encryption increases class file size
* Runtime decryption has minimal performance impact
* When using ProGuard or R8, ensure proper configuration to avoid conflicts

## License

StrGuard is licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).

