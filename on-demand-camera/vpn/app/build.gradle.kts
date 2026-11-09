// :app — APK thử nghiệm nhỏ để kiểm trên MÁY THẬT việc nhúng Tailscale:
// dán auth key → bật lõi Tailscale → đăng nhập → (kiểm máy có join tailnet không).
// KHÔNG phải app OnCam thật; chỉ để xác nhận phần VPN chạy được trước khi ghép.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.cnldg.oncam.vpntest"
    compileSdk = 36
    defaultConfig {
        applicationId = "vn.cnldg.oncam.vpntest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        // Chỉ dựng bản debug (ký khoá debug mặc định) cho tiện cài thử.
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":lib"))
    // Bundle libtailscale.aar vào APK (khác :lib chỉ compileOnly).
    implementation(group = "", name = "libtailscale", ext = "aar")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.core:core-ktx:1.16.0")
}
