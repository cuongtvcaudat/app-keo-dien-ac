import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module Kotlin/JVM thuần (không phụ thuộc Android) để unit test chạy nhanh trên JVM.
// Chứa: giao thức lệnh/trạng thái/sự kiện, ký HMAC, wrapper MQTT client.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    // Lộ ra API công khai (StateFlow/SharedFlow, @Serializable) nên dùng api(...).
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Paho được bọc kín bên trong MqttClientWrapper.
    implementation(libs.paho.mqttv3)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.moquette.broker)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
