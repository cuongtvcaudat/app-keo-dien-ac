// Module :vpn — glue nhúng Tailscale. Dựng ĐỘC LẬP (settings.gradle.kts riêng) để
// biên dịch đối chiếu libtailscale mà KHÔNG đụng build app chính. libtailscale.jar
// (classes.jar rút từ libtailscale.aar) chỉ dùng lúc biên dịch (compileOnly).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.cnldg.oncam.vpn"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// DSL mới của Kotlin 2.x (kotlinOptions cũ đã bỏ).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // API của Tailscale: chỉ cần lúc biên dịch; khi ghép vào app thật sẽ dùng cả .aar.
    compileOnly(files("libs/libtailscale.jar"))
    implementation("androidx.security:security-crypto:1.0.0")
}
