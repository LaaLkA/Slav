rootProject.name = "slav"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // JetBrains' caching mirror of Google Maven, consulted first for androidx artifacts:
        // dl.google.com intermittently answers 404 for existing artifacts from some regions,
        // and Gradle remembers such a miss instead of retrying it on the next build.
        maven("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2") {
            name = "GoogleMavenMirror"
            content { includeGroupByRegex("androidx\\..*") }
        }
        mavenCentral()
        google()
    }
}

include(":core", ":data", ":app")
