import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "io.github.weg2022"
version = providers.gradleProperty("strguardVersion").getOrElse("2.0.0-SNAPSHOT")

val androidGradlePluginVersion = "8.13.2"
val kotlinGradlePluginVersion = "2.1.21"

repositories {
    google()
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

dependencies {
    implementation(gradleApi())
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinGradlePluginVersion")
    compileOnly("com.android.tools.build:gradle-api:$androidGradlePluginVersion")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    compilerOptions.freeCompilerArgs.addAll(
        "-Xno-param-assertions",
        "-Xno-call-assertions",
        "-Xno-receiver-assertions",
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
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
