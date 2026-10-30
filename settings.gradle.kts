pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
        create("sylibs") {
            from(files("gradle/sy.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
        // KMK -->
        // androidx.webgpu isn't on Google's Maven yet. It is carried in-tree, pinned: the build
        // :webgpuviewer's upstream uses republished its 1.0.0-dev06 in place with a native library
        // that no longer matched its classes, and every build after that shipped a reader that
        // couldn't start. See gradle/vendor/README.md.
        exclusiveContent {
            forRepository {
                maven(url = rootDir.resolve("gradle/vendor"))
            }
            filter { includeGroup("androidx.webgpu") }
        }
        // KMK <--
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Komikku"
include(":app")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
// KMK -->
include(":i18n-kmk")
include(":flagkit")
// KMK <--
// SY -->
include(":i18n-sy")
// SY <--
// KMK -->
include(":webgpuviewer")
// KMK <--
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
