import java.time.LocalDateTime

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

    // ImageIO WebP decode + JPEG encode, for server-side cover thumbnailing.
    implementation(libs.bundles.twelvemonkeys)

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    implementation(libs.logback.classic) // was runtimeOnly — LogBuffer attaches a logback appender
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

// ---- Build stamp: bake version / commit / date / recent changelog into resources at build time ----
// Read at runtime by the /api/version endpoint so every device can show exactly which build it runs.
val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val generateBuildInfo by tasks.registering {
    outputs.dir(buildInfoDir)
    outputs.upToDateWhen { false } // always re-stamp: commit/date change without source edits
    doLast {
        fun git(vararg a: String): String = runCatching {
            val p = ProcessBuilder(listOf("git", *a)).directory(rootDir).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrDefault("")
        val dir = buildInfoDir.get().asFile.apply { mkdirs() }
        val commit = git("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
        dir.resolve("build-info.properties").writeText(
            "version=0.1.0\ncommit=$commit\nbuildTime=${LocalDateTime.now().withNano(0)}\n",
        )
        // sha \t date \t subject, newest first — parsed into the changelog list at runtime.
        dir.resolve("changelog.tsv").writeText(git("log", "-n", "50", "--pretty=format:%h\t%ad\t%s", "--date=short"))
    }
}
sourceSets.named("main") { resources.srcDir(buildInfoDir) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }

