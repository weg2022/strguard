# StrGuard 2 Threat Model

## Security objective

StrGuard 2 raises the cost of recovering string literals from a shipped JVM or Android artifact. It prevents cleartext extraction with `strings`, constant-pool scans, and the generic bytecode interpreter that can recover StrGuard 1.x XOR payloads.

StrGuard is not a secret store. A process that can execute the application, call generated gateways, instrument JNI, or read process memory can recover strings after use. Credentials and signing material must remain outside shipped artifacts.

## Adversaries

| Level | Capability | Required outcome |
| --- | --- | --- |
| L0 | Runs `strings`, unzip, or a constant-pool scanner | No protected plaintext or release seed is present |
| L1 | Runs a generic StrGuard 1.x bytecode decoder | Version 2 call sites do not match the fixed decoder contract |
| L2 | Reverse engineers one build's Native library | Recovery remains possible, but requires build-specific Native analysis |
| L3 | Controls or instruments the running process | Out of scope; plaintext necessarily reaches a JVM `String` |

## Build and runtime boundaries

- The 256-bit release seed is supplied by `strGuard.releaseSeedHex` or `STRGUARD_RELEASE_SEED_HEX`.
- The seed is a build secret. It must not be committed, logged, published as an artifact, or retained in shared build intermediates.
- A build-specific master key is derived with HKDF-SHA-256 from the release seed, module identity, and input digest.
- Each call site receives a deterministic pseudorandom 128-bit capability and one of eight generated gateway names.
- Each record has an independently derived key and deterministic nonce. Nonce reuse is safe only because record keys are unique.
- ChaCha20-Poly1305 authenticates the vault header, build ID, capability, gateway, plaintext length, and ciphertext.
- Native key shares are independently masked and permuted per build, then reconstructed through volatile reads to prevent release/LTO constant folding of the master key.
- The JVM artifact contains capabilities and generated Native declarations, not a JVM decoder or a complete decryption key.
- The Native plaintext buffer is zeroized after creating the Java string. Plaintext JVM strings are not cached by StrGuard.

## Vault format

All integer fields are little-endian.

```text
header:
  magic[4] = "SGV2"
  version:u8 = 2
  build_id[16]
  record_count:u32

record:
  body_length:u32
  capability[16]
  gateway:u8
  nonce[12]
  plaintext_length:u32
  ciphertext_length:u32
  ciphertext[ciphertext_length]
  padding_length:u16
  padding[padding_length]
```

The AEAD associated data is `magic || version || build_id || capability || gateway || plaintext_length`.

## Verification gates

- The compatibility test decrypts generated records independently of Rust and rejects a one-bit ciphertext mutation.
- The attack harness reconstructs a 1.x XOR string directly from bytecode instructions.
- The same harness must find no 1.x decoder contract in transformed 2.0 classes.
- Transformed class files, vault bytes, and Native binaries must not contain protected plaintext or the release seed as contiguous bytes.
- Native binaries must not contain the reconstructed master key or any unencoded key share as a contiguous byte sequence.
- Functional tests must execute protected Java and Kotlin/JVM code through the generated JNI bridge.

## Residual risks

- The Native binary contains enough build-specific material to decrypt its own vault. A determined analyst can recover it.
- Release seed exposure compromises reproducibility domains that share that seed; use separate seeds for separate trust domains.
- Compiler, linker, Gradle, JDK, and Rust toolchain reproducibility is platform-specific and must be verified in CI.
- Reflection-sensitive Kotlin libraries can fail when metadata removal is enabled.
