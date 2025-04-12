plugins {
    id("java")
    id("maven-publish")
}


repositories {
    mavenCentral()
}


java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {}

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