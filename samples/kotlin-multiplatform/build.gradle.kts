import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("io.github.weg2022.strguard")
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    js(IR) {
        nodejs()
    }
}

strGuard {
    stringGuardPackages.set(listOf("sample.multiplatform"))
    strictStringCoverage.set(true)
}
