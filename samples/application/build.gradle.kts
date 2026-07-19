plugins {
    application
    id("io.github.weg2022.strguard")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("sample.application.Main")
}

strGuard {
    stringGuardPackages.set(listOf("sample.application"))
}
