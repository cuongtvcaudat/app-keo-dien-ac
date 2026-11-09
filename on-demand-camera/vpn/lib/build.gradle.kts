// :lib — thư viện glue nhúng Tailscale. libtailscale chỉ cần lúc biên dịch (compileOnly);
// APK thật (module :app) mới bundle cả .aar.
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(group = "", name = "libtailscale", ext = "aar")
    implementation("androidx.security:security-crypto:1.0.0")
}
