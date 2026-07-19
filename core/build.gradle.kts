// core — the headless engine: extension loader, source manager, download manager,
// converter, status/logging. No UI, no direct DB access (talks to repository interfaces).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":source-api"))
    implementation(libs.slf4j.api)
    implementation(libs.kotlinlogging)
    implementation(libs.coroutines.core)

    // Extension install/load pipeline
    implementation(libs.apk.parser)
    implementation(libs.dex2jar.translator)
    implementation(libs.dex2jar.tools)
    implementation(libs.asm) // BytecodeEditor rewrites SimpleDateFormat references
    implementation(libs.serialization.protobuf) // repo index_v2 (index.pb) is gzipped protobuf

    testImplementation(kotlin("test"))
}
