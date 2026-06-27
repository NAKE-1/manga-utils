rootProject.name = "manga-utils"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
        // JCEF (webview API the AndroidCompat webkit stubs compile against) + its native deps
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven("https://jogamp.org/deployment/maven")
    }
}

include(
    "android-compat",
    "source-api",
    "core",
    "data",
    "cli",
    "gui",
    "desktop",
)
