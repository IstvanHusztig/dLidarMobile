pluginManagement {
    repositories {
        google()
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

rootProject.name = "dLidarMobile"

include(
    ":domain",
    ":data:protocol",
    ":data:scanner",
    ":infra:ble",
    ":presentation",
    ":debug:linktest",
    ":testing",
    ":app",
)
