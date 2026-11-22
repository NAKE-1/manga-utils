rootProject.name = "manga-utils"

// Optional build-time Maven mirror. If MU_MAVEN_MIRROR is set (e.g. http://100.67.109.13:8099), ALL plugin
// + dependency repos resolve through it instead of the public ones. Used so worker nodes - which egress
// through a Mullvad exit that Google's Maven CDN refuses - fetch deps from main over the tailnet (tailnet
// traffic bypasses the exit; main's clean egress populates the cache). Unset (main, dev machines) = normal
// direct resolution, unchanged. The env is read INSIDE each block because `pluginManagement` must stay the
// first statement in this file. Mirror path layout + setup: docs/maven-mirror.md.
pluginManagement {
    repositories {
        val m = System.getenv("MU_MAVEN_MIRROR")?.trim()?.trimEnd('/')?.ifBlank { null }
        if (m != null) {
            maven("$m/gradle-plugins"); maven("$m/central"); maven("$m/google"); maven("$m/compose-dev")
        } else {
            gradlePluginPortal()
            mavenCentral()
            google()
            maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        val m = System.getenv("MU_MAVEN_MIRROR")?.trim()?.trimEnd('/')?.ifBlank { null }
        if (m != null) {
            maven("$m/central"); maven("$m/google"); maven("$m/jitpack"); maven("$m/jetbrains-ij"); maven("$m/jogamp")
        } else {
            mavenCentral()
            google()
            maven("https://jitpack.io")
            // JCEF (webview API the AndroidCompat webkit stubs compile against) + its native deps
            maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
            maven("https://jogamp.org/deployment/maven")
        }
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
    "server",
)
