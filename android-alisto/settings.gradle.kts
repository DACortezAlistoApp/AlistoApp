pluginManagement {
    repositories {
        google()
        // Use Google's Maven Central mirror before Gradle Plugin Portal & Maven Central to avoid GitHub runner HTTP 429 limits
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "AlistoPlatform"
include(":core-kernel")
include(":app-customer")
include(":app-contractor")
