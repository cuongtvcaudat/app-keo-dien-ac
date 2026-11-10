import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Vai trò CAMERA (0.13: là thư viện của app gộp :app). Giữ namespace + tài nguyên riêng; đóng gói APK do :app lo.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "vn.cnldg.oncam.node"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        resources {
            // Moquette kéo theo nhiều jar Netty, trùng các file META-INF. Cần cho APK androidTest của thư viện này
            // (APK app cuối cùng đã lọc ở module :app). Giữ khớp với khối packaging của :app.
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
    implementation(project(":rtc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)

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

    // Broker MQTT nhúng (đề xuất P1). Bỏ các thư viện chỉ dùng khi bật tính năng tương ứng của Moquette:
    // bugsnag (gửi báo lỗi ra dịch vụ ngoài), metrics/librato, HikariCP/commons-codec (xác thực bằng DB/file),
    // epoll (native Linux), reload4j (log4j). Đã kiểm tra mã Moquette 0.17: các lớp này chỉ được gọi khi bật cấu hình.
    implementation(libs.moquette.broker) {
        exclude(group = "com.bugsnag")
        exclude(group = "com.librato.metrics")
        exclude(group = "io.dropwizard.metrics")
        exclude(group = "com.zaxxer")
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "commons-codec")
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j")
    }

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test.junit)
}
