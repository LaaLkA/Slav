import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

/** The version of the installers, also written into exported settings files (spec 9). */
val appVersion = "1.0.0"

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The Compose UI test rule is a JUnit 4 rule, so the whole module runs on JUnit 4.
    useJUnit()
    // AppPaths.default() resolves against user.home: a test that reaches for the default location
    // lands in the build directory instead of the real user data directory.
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "io.github.laalka.slav.app.MainKt"
        // Read by the app at runtime; passed to `run` and to the packaged launcher alike.
        jvmArgs += listOf("-Dslav.appVersion=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Slav"
            packageVersion = appVersion
            // Output of `./gradlew :app:suggestRuntimeModules`; re-run it after adding dependencies.
            modules("java.instrument", "jdk.unsupported")

            windows {
                // Must never change: a new .msi replaces the installed one only when this UUID matches.
                upgradeUuid = "ac927098-2ae4-4dac-9c2d-a8dc800bc8fa"
                menuGroup = "Slav"
                perUserInstall = true
            }
            macOS {
                bundleID = "io.github.laalka.slav"
            }
        }
    }
}
