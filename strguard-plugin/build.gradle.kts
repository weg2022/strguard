plugins {
    id("java")
    id("groovy")
    id("java-gradle-plugin")
    id("com.gradle.plugin-publish") version "2.0.0"
}

group = "io.github.weg2022"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("com.squareup:javapoet:1.13.0")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-util:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
}

gradlePlugin {
    website.set("https://github.com/weg2022/strguard")
    vcsUrl.set("https://github.com/weg2022/strguard.git")
    plugins {
        register("strguardPlugin") {
            id = "io.github.weg2022.strguard"
            implementationClass = "io.github.weg2022.strguard.StrGuardPlugin"
            displayName = "StrGuard Gradle Plugin"
            description = "Gradle plugin for encrypting class strings"
            tags = listOf("strguard", "encrypt")
        }
    }
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}


tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

