import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module Kotlin/JVM thuần (không phụ thuộc Android) để unit test chạy nhanh trên JVM.
// Chứa: giao thức lệnh/trạng thái (Phase 1) và wrapper MQTT client (Phase 1).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
