pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mihon-puente"

include(":core")
include(":desktopApp")

fun hasAndroidSdk(): Boolean {
    if (!System.getenv("ANDROID_HOME").isNullOrBlank()) return true
    if (!System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()) return true
    val properties = file("local.properties")
    if (!properties.exists()) return false
    return properties.readLines().any { line ->
        line.trim().startsWith("sdk.dir=") && line.substringAfter("=").isNotBlank()
    }
}

if (hasAndroidSdk()) {
    include(":androidApp")
}
