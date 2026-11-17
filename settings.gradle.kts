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

rootProject.name = "xtxp-android"

// The Kotlin side of the Rust muppet: UniFFI bindings and the app's pure
// logic. Its own build, so it runs on a plain JVM without the Android SDK.
includeBuild("core")
include(":app")
