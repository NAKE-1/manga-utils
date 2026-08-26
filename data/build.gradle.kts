// data — persistence: Exposed ORM over SQLite, tables, repository implementations.
// Phase 2+ adds exposed + sqlite-jdbc and the schema/repositories.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}
