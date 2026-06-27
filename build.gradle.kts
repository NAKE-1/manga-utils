import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "mangautils"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                // Vendored Suwayomi sources use context parameters (Kotlin 2.2+).
                freeCompilerArgs.add("-Xcontext-parameters")
            }
        }
        // We run on JDK 25 but emit JVM 21 bytecode; keep the Java tasks in lockstep
        // with Kotlin so the two targets don't disagree.
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
