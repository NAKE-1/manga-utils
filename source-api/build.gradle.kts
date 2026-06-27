// source-api — the eu.kanade.tachiyomi.{source,network,util} interfaces and models that
// real Tachiyomi/Mihon extensions are compiled against. Must stay binary-compatible with
// them, so the package names and signatures are preserved exactly as vendored from Suwayomi.
// Dependencies are exposed via `api` because loaded extensions need them on the runtime
// classpath (okhttp/jsoup/rxjava/injekt/coroutines + the android.* stubs).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":android-compat"))

    // Tachiyomi extension runtime stack
    api(libs.injekt)
    api(libs.koin.core)
    api(libs.bundles.okhttp)
    api(libs.okio)
    api(libs.rxjava)
    api(libs.jsoup)

    api(libs.coroutines.core)
    api(libs.coroutines.jdk8)
    api(libs.serialization.json)
    api(libs.serialization.json.okio)
    api(libs.serialization.protobuf)
    api(libs.serialization.xml.core) // AppModule provides a shared XML() (ComicInfo etc.)
    api(libs.serialization.xml)
    api(libs.kotlinlogging)
    api(libs.kotlin.reflect)

    // natural-order title sorting + epub/zip parsing used by util/*
    api(libs.sort)
    api(libs.commonscompress)

    compileOnly(libs.android.annotations)
}
