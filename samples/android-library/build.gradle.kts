plugins {
    id("com.android.library") version "8.13.2"
    id("io.github.weg2022.strguard")
}

android {
    namespace = "sample.android.library"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 21
        ndk.abiFilters += "arm64-v8a"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

strGuard {
    androidAbis.set(setOf("arm64-v8a"))
    stringGuardPackages.set(listOf("sample.android.library"))
    strictStringCoverage.set(true)
}
