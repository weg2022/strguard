plugins {
    id("com.android.library") version "8.13.2"
    id("io.github.weg2022.strguard")
}

android {
    namespace = "sample.android.library"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

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
    stringGuardPackages.set(listOf("sample.android.library"))
}
