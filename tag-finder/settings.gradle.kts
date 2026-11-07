pluginManagement {
    repositories {
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val coreOnly = providers.gradleProperty("coreOnly").isPresent

dependencyResolutionManagement {
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}

rootProject.name = "tag-finder"

// The signal-processing core is plain Kotlin/JVM and builds anywhere.
include(":core")

// The Android app needs the Android Gradle Plugin from Google's Maven repo.
// Pass -PcoreOnly to build and test just the core (e.g. on CI without the Android SDK).
if (!coreOnly) {
    include(":app")
}
