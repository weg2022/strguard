plugins {
    id("com.android.application") version "8.13.2"
    id("io.github.weg2022.strguard")
}

android {
    namespace = "sample.android.application"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "sample.android.application"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk.abiFilters += "arm64-v8a"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

strGuard {
    stringGuardPackages.set(listOf("sample.android.application"))
}
