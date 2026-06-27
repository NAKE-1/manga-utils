// gui — a basic Swing test GUI exposing the engine's features for visual testing.
// Swing ships with the JDK (no new deps). The polished desktop GUI + web server come later.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)
}

application {
    applicationName = "mu-gui"
    mainClass.set("mangautils.gui.MainKt")
    applicationDefaultJvmArgs =
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-DMU_DATA_DIR=${rootProject.projectDir.resolve("data").absolutePath}",
        )
}

// Large dependency set → collapse the launcher classpath to a wildcard (cmd line-length limit).
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace(Regex("set CLASSPATH=.*"), "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"),
        )
    }
}
