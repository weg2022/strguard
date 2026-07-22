plugins {
    `java-library`
    id("io.github.weg2022.strguard")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

strGuard {
    stringGuardPackages.set(listOf("sample.library"))
    strictStringCoverage.set(true)
}
