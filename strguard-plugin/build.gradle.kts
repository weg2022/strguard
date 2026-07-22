import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21"
    `java-gradle-plugin`
    `maven-publish`
    jacoco
    id("com.gradle.plugin-publish") version "2.1.1"
    id("com.gradleup.shadow") version "9.1.0"
    id("com.diffplug.spotless") version "8.7.0"
}

jacoco {
    toolVersion = "0.8.13"
}

group = "io.github.weg2022"
version = providers.gradleProperty("strguardVersion").getOrElse("2.0.0")

val androidGradlePluginVersion = "8.13.2"
val kotlinGradlePluginVersion = "2.1.21"
val composeGradlePluginVersion = "1.8.2"
val asmVersion = "9.10.1"

val relocatedAsm by configurations.creating

repositories {
    google()
    mavenCentral()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(
            mapOf(
                "ktlint_code_style" to "intellij_idea",
                "max_line_length" to "off",
                "ktlint_standard_no-wildcard-imports" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(
            mapOf(
                "ktlint_code_style" to "intellij_idea",
                "max_line_length" to "off",
                "ktlint_standard_no-wildcard-imports" to "disabled",
            ),
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

dependencies {
    implementation(gradleApi())
    compileOnly("org.ow2.asm:asm:$asmVersion")
    compileOnly("org.ow2.asm:asm-commons:$asmVersion")
    relocatedAsm("org.ow2.asm:asm:$asmVersion")
    relocatedAsm("org.ow2.asm:asm-commons:$asmVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinGradlePluginVersion")
    compileOnly("com.android.tools.build:gradle-api:$androidGradlePluginVersion")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:$composeGradlePluginVersion")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.ow2.asm:asm:$asmVersion")
    testImplementation("org.ow2.asm:asm-commons:$asmVersion")
}

tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(relocatedAsm)
    relocate("org.objectweb.asm", "io.github.weg2022.strguard.internal.asm")
}

tasks.jar {
    archiveClassifier.set("main")
}

configurations {
    named("apiElements") {
        outgoing.artifacts.clear()
        outgoing.variants.clear()
        outgoing.artifact(tasks.shadowJar)
    }
    named("runtimeElements") {
        outgoing.artifacts.clear()
        outgoing.variants.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.setFrom(tasks.shadowJar)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    compilerOptions.freeCompilerArgs.addAll(
        "-Xno-param-assertions",
        "-Xno-call-assertions",
        "-Xno-receiver-assertions",
        "-Xjdk-release=11",
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.shadowJar)
    doFirst {
        systemProperty(
            "strguard.shadowJar",
            tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.29".toBigDecimal()
            }
        }
        rule {
            includes = listOf(
                "io.github.weg2022.strguard.vault.*",
                "io.github.weg2022.strguard.crypto.*",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<ProcessResources>().configureEach {
    from(rootProject.layout.projectDirectory.dir("native/strguard-runtime")) {
        include("Cargo.toml", "Cargo.lock", "build.rs", "src/**", "vendor.zip")
        into("strguard-native-runtime")
    }
}

gradlePlugin {
    website.set("https://github.com/weg2022/strguard")
    vcsUrl.set("https://github.com/weg2022/strguard")
    plugins {
        create("strguard") {
            id = "io.github.weg2022.strguard"
            implementationClass = "io.github.weg2022.strguard.StrGuardPlugin"
            displayName = "StrGuard Gradle Plugin"
            description = "Native-backed string protection for JVM and Android builds"
            tags.set(listOf("jvm", "android", "native", "obfuscation", "strings"))
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("StrGuard Gradle Plugin")
            description.set("Native-backed string protection for JVM and Android builds")
            url.set("https://github.com/weg2022/strguard")

            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("weg2022")
                    name.set("weilanyu")
                    email.set("weg2022@outlook.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/weg2022/strguard.git")
                developerConnection.set("scm:git:ssh://github.com/weg2022/strguard.git")
                url.set("https://github.com/weg2022/strguard")
            }
        }
    }
}
