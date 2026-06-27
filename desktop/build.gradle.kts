// desktop — the modern Compose for Desktop GUI (the real app). The Swing `gui` module remains
// a throwaway feature-test tool. Reuses the `core` engine directly.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.coroutines.core)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    runtimeOnly(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "mangautils.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // Share the same data dir as the CLI/Swing tools (extensions, library, settings, downloads)
        // regardless of the launch working directory.
        jvmArgs += "-DMU_DATA_DIR=${rootProject.projectDir.resolve("data").absolutePath}"
    }
}
