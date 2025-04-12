
plugins {
    id("java")
    id("groovy")
    id("maven-publish")
    id("java-gradle-plugin")
}


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
    implementation(project(":strguard-api"))
    implementation("com.squareup:javapoet:1.13.0")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-util:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
}


tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    repositories {
        maven {
            url = uri("file://${System.getProperty("user.home")}/repo")
        }
    }
    publications {
        create<MavenPublication>(project.name) {
            from(components["java"])
        }
    }
}

gradlePlugin {
    plugins {
        create("strguardPlugin") {
            id = "org.prime4j.strguard"
            implementationClass = "org.prime4j.strguard.StrGuardPlugin"
        }
    }
}