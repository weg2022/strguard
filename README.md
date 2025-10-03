明白，我帮你把 README 改成**更地道的英文开源风格**版本，语言更自然、条理更清晰，同时保留所有技术细节和示例：

---

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

StrGuard is published on the Gradle Plugin Portal. You can apply it in your root `build.gradle` or `build.gradle.kts`:

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

## Usage

### Basic Usage

Simply applying the plugin will automatically encrypt all string constants in your project.

### Excluding Specific Classes

Use the `@KeepString` annotation to exclude certain classes from encryption:

```java
import io.github.weg2022.strguard.annotation.KeepString;

@KeepString
public class MyClass {
    private static final String SECRET = "This string will not be encrypted";
}
```

### Custom Encryption/Decryption

Implement the [IStrGuard](file://C:\Users\weg20\IdeaProjects\strguard\strguard-plugin\src\main\groovy\io\github\weg2022\strguard\api\IStrGuard.java#L2-L10) interface. You can refer to the default [StrGuardImpl](file://C:\Users\weg20\IdeaProjects\strguard\strguard-plugin\src\main\groovy\io\github\weg2022\strguard\api\StrGuardImpl.java#L5-L36) for guidance:

```java
public class MyStrGuardImpl implements IStrGuard {
    @Override
    public byte[] encode(String raw, byte[] key) {
        // Custom encryption logic
    }

    @Override
    public String decode(byte[] data, byte[] key) {
        // Custom decryption logic
    }

    @Override
    public boolean apply(String raw) {
        // Decide whether a specific string should be encrypted
        // Recommended: skip non-critical or very long strings
        return true;
    }
}
```

### Custom Key Generators

Implement the [IKeyGenerator](file://C:\Users\weg20\IdeaProjects\strguard\strguard-plugin\src\main\groovy\io\github\weg2022\strguard\api\IkeyGenerator.java#L2-L6) interface, using [HardCodeKeyGenerator](file://C:\Users\weg20\IdeaProjects\strguard\strguard-plugin\src\main\groovy\io\github\weg2022\strguard\api\HardCodeKeyGenerator.java#L6-L27) or [RandomKeyGenerator](file://C:\Users\weg20\IdeaProjects\strguard\strguard-plugin\src\main\groovy\io\github\weg2022\strguard\api\RandomKeyGenerator.java#L5-L28) as references:

```java
public class MyKeyGenerator implements IKeyGenerator {
    @Override
    public byte[] generate(String text) {
        // Generate a key based on the string content
        return ("MyKey" + text.length()).getBytes(StandardCharsets.UTF_8);
    }
}
```

Then configure it in your build script:

```kotlin
strGuard {
    keyGenerator = MyKeyGenerator()
}
```

## Building the Project

After applying and configuring the plugin, run:

```bash
./gradlew build
```

StrGuard will automatically encrypt string constants during compilation.

## Mapping Files

If `generateMappings` is enabled, two mapping files are generated:

1. `string_guard_mapping.txt` – maps original strings to encrypted values
2. `remove_metadata_mapping.txt` – maps removed metadata

These files are located in `build/outputs/mapping/strguard/` and help with debugging.

## Notes

* Certain system classes and special strings (e.g., class names, method descriptors) are automatically skipped
* Encryption increases class file size
* Runtime decryption has minimal performance impact
* When using ProGuard or R8, ensure proper configuration to avoid conflicts

## License

StrGuard is licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).

