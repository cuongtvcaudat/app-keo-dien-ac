import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// App chạy trên điện thoại xem (viewer).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "vn.cnldg.oncam.viewer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "vn.cnldg.oncam.viewer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Khoá ký dùng chung cho cả debug và release, mật khẩu là giá trị mặc định công khai "android"
            // (không phải bí mật): APK build ở các lần CI khác nhau cài đè lên nhau được, không phải gỡ app.
            // Đánh đổi (chủ dự án đã chọn): ai có repo cũng ký được APK cùng chữ ký → chỉ cài APK từ trang Release
            // của repo này. Xem docs/ARCHITECTURE.md (D30).
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Bản dùng thật: không debuggable, không ghi log (Timber chỉ bật ở debug).
            signingConfig = signingConfigs.getByName("debug")
            // Chưa bật R8 (thu gọn mã): broker Moquette/Netty nạp lớp bằng reflection, thu gọn sai sẽ lỗi lúc chạy
            // trên máy thật mà test JVM không phát hiện được. Bật khi có test chạy trên bản release (D31).
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Annotation trên tham số constructor (vd. @ApplicationContext của Hilt) áp dụng cho cả param và field
        // — hành vi mặc định tương lai của Kotlin (KT-73255), bật sớm để hết cảnh báo.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)
    implementation(libs.zxing.android.embedded)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test.junit)
}
