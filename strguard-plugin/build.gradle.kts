import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21"
    `java-gradle-plugin`
    `maven-publish`
    signing
}

group = "io.github.weg2022"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(gradleApi())
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xno-param-assertions",
        "-Xno-call-assertions",
        "-Xno-receiver-assertions",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<ProcessResources>().configureEach {
    from(rootProject.layout.projectDirectory.dir("native/strguard-runtime")) {
        include("Cargo.toml", "Cargo.lock", "src/**")
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
            description = "Gradle plugin for JVM string obfuscation"
            tags.set(listOf("jvm", "asm", "obfuscation", "strings"))
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("StrGuard Gradle Plugin")
            description.set("Gradle plugin for JVM string obfuscation")
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
    repositories {
        maven {
            name = "sonatype"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = providers.gradleProperty("ossrhUsername").orNull
                password = providers.gradleProperty("ossrhPassword").orNull
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
