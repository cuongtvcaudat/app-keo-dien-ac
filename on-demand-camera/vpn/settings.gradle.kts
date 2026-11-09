// Dự án Gradle ĐỘC LẬP cho phần nhúng VPN (tách khỏi app chính):
//  - :lib   thư viện glue Tailscale (OncamAppContext, OncamVpnService, controller)
//  - :app   APK thử nghiệm nhỏ: dán auth key → bật Tailscale → xem trạng thái
// libtailscale.aar đặt ở vpn/libs/ (CI chép vào từ bước dựng AAR).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.12.0"
        id("com.android.application") version "8.12.0"
        id("org.jetbrains.kotlin.android") version "2.2.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // libtailscale.aar (BSD-3) — CI chép vào vpn/libs/ sau khi dựng.
        flatDir { dirs("${rootDir}/libs") }
    }
}

rootProject.name = "oncam-vpn"
include(":lib", ":app")
