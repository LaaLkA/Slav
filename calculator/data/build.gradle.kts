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

tasks.test {
    useJUnitPlatform()
    // AppPaths.default() resolves against user.home: pointing it into the build directory keeps a
    // test that reaches for the default location away from the real user data directory.
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}
