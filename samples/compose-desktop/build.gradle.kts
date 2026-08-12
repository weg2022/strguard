import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("io.github.weg2022.strguard")
}

kotlin {
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets {
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "sample.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "strguard-compose-desktop-sample"
            packageVersion = "1.0.0"
            // ProGuard 模式自动裁剪模块会漏掉运行时所需模块:
            // jdk.accessibility(Windows 默认启用的 AccessBridge 加载会 AWTError)、
            // jdk.unsupported(sun.misc.Unsafe 的常见依赖方)
            modules("jdk.accessibility", "jdk.unsupported")
        }

        buildTypes.release {
            proguard {
                // StrGuard 会在 ProGuard 消费受保护 JAR 时校验并回写 bridge 类
                obfuscate.set(true)
            }
        }
    }
}

strGuard {
    // 示例 seed:仅用于本地验证,生产环境请通过 STRGUARD_RELEASE_SEED_HEX 注入
    releaseSeedHex.set("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    stringGuardPackages.set(listOf("sample"))
    // Compose 编译器会在 composable 方法上生成 FunctionKeyMeta 注解(key 字符串),
    // 该内部机制字符串无法保护;strictStringCoverage 会因此失败,示例不开启。
    // removeMetadata 移除 SourceDebugExtension/kotlin.Metadata 等编译器元数据注解
    removeMetadata.set(true)
    removeMetadataPackages.set(listOf("sample"))
    consoleOutput.set(true)
}
