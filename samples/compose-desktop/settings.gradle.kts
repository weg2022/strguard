pluginManagement {
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Desktop 的部分传递依赖(androidx lifecycle/savedstate 官方坐标)在 google()
        google()
    }
}

rootProject.name = "strguard-compose-desktop-sample"
