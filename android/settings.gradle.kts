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

rootProject.name = "noiz"

// --- app ------------------------------------------------------------------
include(":app")

// --- core (feature:* may depend on core:* only; core:playback never depends on
//     feature:* — PRD §3.2) -------------------------------------------------
include(":core:common")
include(":core:designsystem")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:datastore")
include(":core:playback")
include(":core:download")

// --- features -------------------------------------------------------------
include(":feature:home")
include(":feature:search")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:lyrics")
include(":feature:queue")
include(":feature:connect")
include(":feature:upload")
include(":feature:settings")

// --- performance ----------------------------------------------------------
include(":benchmark")
