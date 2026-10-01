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
        // Vendored: JitPack can no longer build arkon/FlexibleAdapter@c8013533
        // (its 2021-era buildscript requires nu.studer:java-ordered-properties:1.0.1,
        // which no longer exists on Maven Central), so the AAR was rebuilt from the
        // pinned sources and is vendored under maven-repo/.
        exclusiveContent {
            forRepository {
                maven(url = uri("maven-repo"))
            }
            filter { includeGroup("com.github.arkon.FlexibleAdapter") }
        }
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
        // KMK -->
        // androidx.webgpu isn't on Google's Maven yet - the build :webgpuviewer's upstream uses.
        exclusiveContent {
            forRepository {
                maven(url = "https://raw.githubusercontent.com/mpreg-ca/androidx-webgpu-repo/main")
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
