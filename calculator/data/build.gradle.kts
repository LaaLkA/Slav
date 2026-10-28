plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(17) }

sqldelight {
    databases {
        create("SlavDatabase") {
            packageName.set("io.github.laalka.slav.data.db")
            // One snapshot of the schema per released version; verifyMigrations replays the .sqm files
            // on each of them and compares the result with the current schema.
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    // Repositories expose core types (settings, calculations) in their signatures.
    api(project(":core"))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

// The plugin's snapshots of past schema versions; the migration test opens a database made from the first.
val schemaSnapshots = layout.projectDirectory.dir("src/main/sqldelight/databases")

tasks.test {
    useJUnitPlatform()
    inputs.dir(schemaSnapshots)
    systemProperty("slav.schemaSnapshots", schemaSnapshots.asFile.absolutePath)
    // AppPaths.default() resolves against user.home: pointing it into the build directory keeps a
    // test that reaches for the default location away from the real user data directory.
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}
