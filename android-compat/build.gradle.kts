// AndroidCompat — JVM reimplementations/stubs of the Android APIs that
// Tachiyomi/Mihon extensions expect at runtime. Vendored from Suwayomi-Server
// (Apache-2.0 / MPL-2.0; see NOTICE). The patched android.jar stub is pulled as a
// published artifact (com.github.Suwayomi:android-jar) rather than generated locally.
// The AndroidCompat/Config sources are folded into this module (package xyz.nulldev.ts.config).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // android.* stub classes we don't override. Generated locally by
    // generate-android-jar.ps1 (SDK 30 stub with overridden/JVM-conflicting classes stripped).
    api(files("lib/android.jar"))

    compileOnly(libs.xmlpull)
    compileOnly(libs.apksig)
    compileOnly(libs.android.annotations)

    // duktape/quickjs substitute for extensions' JS evaluation
    implementation(libs.bundles.polyglot)
    // SharedPreferences impl backed by java.util.prefs
    implementation(libs.bundles.settings)
    // Android's SimpleDateFormat behaviour
    implementation(libs.icu4j)
    // OpenJDK lacks a native JPEG encoder / WEBP decoder
    implementation(libs.bundles.twelvemonkeys)
    implementation(libs.imageio.webp)

    // Config module deps + general shared deps the vendored code uses
    implementation(libs.config)
    implementation(libs.config4k)
    implementation(libs.appdirs)
    implementation(libs.kotlinlogging)
    implementation(libs.jackson.annotations) // androidx.preference stubs are @JsonProperty-annotated
    implementation(libs.logback.classic) // Config/Logging.kt configures logback directly
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.jdk8)
    implementation(libs.serialization.json)
    implementation(libs.koin.core)
    implementation(libs.rxjava)
    implementation(libs.jsoup)
    implementation(libs.slf4j.api)

    // PackageManager emulation: APK parsing + dex translation
    implementation(libs.apk.parser)
    implementation(libs.dex2jar.translator)
    implementation(libs.dex2jar.tools)

    // Webview API the webkit stubs compile against (not invoked in v1)
    implementation(libs.jcef)
}
