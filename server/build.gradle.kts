// server — the Ktor web backend for the phone-first web UI. Reuses the `core` engine directly
// (same as `desktop`), exposes it over HTTP, and serves the built React frontend from resources.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val ktorVersion = "3.1.3"

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    runtimeOnly(libs.logback.classic)
}

application {
    mainClass = "mangautils.server.MainKt"
}
