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

## Notes

* Certain system classes and special strings (e.g., class names, method descriptors) are automatically skipped
* Encryption increases class file size
* Runtime decryption has minimal performance impact
* When using ProGuard or R8, ensure proper configuration to avoid conflicts

## License

StrGuard is licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).

