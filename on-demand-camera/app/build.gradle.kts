import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// App gộp (0.13): 1 APK cho cả 2 vai trò. Mở lên chọn "Camera" hoặc "Máy xem" (RootActivity) rồi vào
// giao diện tương ứng (camera-node / viewer, nay là thư viện). Mọi cấu hình đóng gói APK (ký, ABI, nén
// thư viện native, loại trừ META-INF của Moquette) nằm ở đây vì APK cuối cùng do module này tạo.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "vn.cnldg.oncam.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "vn.cnldg.oncam"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 14
        versionName = "0.13.0"
    }

    signingConfigs {
        getByName("debug") {
            // Khoá ký dùng chung cho debug và release (mật khẩu mặc định công khai "android", không phải bí mật):
            // APK các lần CI cài đè lên nhau được. Xem docs/ARCHITECTURE.md (D30).
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Thư viện native WebRTC (~11 MB mỗi kiến trúc): debug thêm x86_64 để chạy thử trên emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            // Điện thoại thật đều ARM; CI chạy thử bản release trên emulator x86_64 thì truyền -Poncam.releaseAbis=x86_64.
            val abis = (project.findProperty("oncam.releaseAbis") as String?)?.split(',')
                ?: listOf("arm64-v8a", "armeabi-v7a")
            ndk { abiFilters += abis }
            signingConfig = signingConfigs.getByName("debug")
            // Chưa bật R8: Moquette/Netty nạp lớp bằng reflection, thu gọn sai sẽ lỗi lúc chạy (D31).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Nén thư viện native trong APK (tải về nhẹ hơn ~60%; Android giải nén khi cài).
        jniLibs { useLegacyPackaging = true }
        resources {
            // Netty/H2 (thư viện của broker Moquette) có các file META-INF trùng tên hoặc chỉ dùng cho JVM.
            excludes += setOf(
                "META-INF/io.netty.versions.properties",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/native-image/**",
                "META-INF/versions/**",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rtc"))
    // Hai vai trò, nay là thư viện (giữ namespace + tài nguyên riêng → 40 tên string trùng không xung đột).
    implementation(project(":camera-node"))
    implementation(project(":viewer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)
}
