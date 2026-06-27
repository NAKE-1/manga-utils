// cli — the `mu` command-line front-end (first deliverable). Wires core + data together.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.clikt)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)
}

application {
    applicationName = "mu"
    mainClass.set("mangautils.cli.MainKt")
    // Grant native access so the JNA/JCEF native loads don't print warnings on every run (JDK 21+).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Our dependency set is large (graal, jcef, twelvemonkeys, …); the default generated
// Windows launcher inlines every jar into CLASSPATH and overflows cmd's line-length
// limit. Collapse it to a wildcard so `mu.bat` stays runnable.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText()
                .replace(Regex("set CLASSPATH=.*"), "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"),
        )
    }
}
