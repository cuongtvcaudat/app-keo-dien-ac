pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "on-demand-camera"

include(":shared")
include(":rtc")
include(":camera-node")
include(":viewer")
// App gộp (0.13): 1 APK, mở lên chọn vai trò Camera / Máy xem. camera-node và viewer giờ là thư viện.
include(":app")
