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
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bomb"

// Android application shell + shared Compose UI.
include(":preview")
include(":app-preview")

// Pure-JVM policy/validation/parsing logic. No Android imports, so every rule
// in docs/BOMB_PLAN.md §10's coverage list is testable with plain JUnit — no
// emulator, no Robolectric. Everything privileged decides *what* to do here.
include(":domain")

// The Binder contract: AIDL, the Parcelable models that cross it, BombResult and
// BombCapabilities. The only module shared by the UI and the privileged service,
// which is what stops UI code reaching a privileged implementation directly.
include(":core-api")

// The privileged side: the bound service, caller validation, capability probing
// and the readers that turn device state into the models :core-api carries.
// Executes what :domain decides; contains no policy of its own.
include(":system-service")
