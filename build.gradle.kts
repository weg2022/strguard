buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    dependencies {}
}
allprojects {
    repositories {
        mavenCentral()
        google()
    }
    dependencyLocking {
        lockAllConfigurations()
    }
}

