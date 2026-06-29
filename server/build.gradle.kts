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

// ---- Frontend build: compile the Vite/React app in webui/ into resources/web -----------------
// Runs as part of processResources so `gradlew :server:run` / `start.bat web` always serve a fresh
// UI. Incremental via inputs/outputs (skips when the frontend source is unchanged). Requires Node.
val webuiDir = layout.projectDirectory.dir("webui")
val webOut = layout.projectDirectory.dir("src/main/resources/web")
val npmExe = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

val webInstall by tasks.registering(Exec::class) {
    workingDir = webuiDir.asFile
    inputs.file(webuiDir.file("package.json"))
    outputs.dir(webuiDir.dir("node_modules"))
    commandLine(npmExe, "install", "--no-fund", "--no-audit")
}

val webBuild by tasks.registering(Exec::class) {
    dependsOn(webInstall)
    workingDir = webuiDir.asFile
    inputs.dir(webuiDir.dir("src"))
    inputs.file(webuiDir.file("package.json"))
    inputs.file(webuiDir.file("vite.config.ts"))
    inputs.file(webuiDir.file("index.html"))
    outputs.dir(webOut)
    commandLine(npmExe, "run", "build")
}

tasks.named("processResources") { dependsOn(webBuild) }

