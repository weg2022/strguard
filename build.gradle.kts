
buildscript {
    repositories {
        maven { url = uri("https://plugins.gradle.org/m2/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
        gradlePluginPortal()
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://sandec.jfrog.io/artifactory/repo/") }
        mavenLocal()
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://repo1.maven.org") }
    }
    dependencies {}
}
allprojects {
    group = "org.prime4j"
    version = "1.0.5.14"

    repositories {
        maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/") }
        maven { url = uri("https://jogamp.org/deployment/maven/") }
        maven { url = uri("https://repo1.maven.org/maven2/") }
        maven { url = uri("https://sandec.jfrog.io/artifactory/repo/") }
        mavenLocal()
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://repo1.maven.org") }
    }
}

