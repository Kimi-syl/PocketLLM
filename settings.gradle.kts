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
rootProject.name = "PocketLLM"
// iOS CI passes -PskipAndroid=true: the framework build needs no Android SDK.
if (providers.gradleProperty("skipAndroid").isPresent) {
    include(":core")
    include(":cli")
} else {
    include(":app")
    include(":core")
    include(":cli")
}
