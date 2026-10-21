import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.github.laalka.slav.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Slav"
            packageVersion = "1.0.0"
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
