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

    // For the native dark window title bar on Windows (DwmSetWindowAttribute).
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    runtimeOnly(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "mangautils.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
