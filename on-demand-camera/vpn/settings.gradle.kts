// Dự án Gradle ĐỘC LẬP cho module :vpn (biên dịch kiểm tra, tách khỏi app chính).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.12.0"
        id("org.jetbrains.kotlin.android") version "2.2.10"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "oncam-vpn"
