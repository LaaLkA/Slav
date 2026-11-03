import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

/** The version of the installers, also written into exported settings files (spec 9). */
val appVersion = "1.0.2"

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)
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

// The icons of the installers are the logo, drawn by the generator of the test sources (IconGenerator.kt) and committed:
// `./gradlew :app:generateIcons` redraws them after the logo changes, IconFilesTest fails when they are out of date.
val iconsDir = layout.projectDirectory.dir("icons")

tasks.register<JavaExec>("generateIcons") {
    group = "build"
    description = "Draws the logo into icons/Slav.ico, icons/Slav.icns and icons/Slav.png."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.laalka.slav.app.icons.IconGeneratorKt"
    args(iconsDir.asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true")
}

// The Compose DSL has no switch for jpackage's `--win-shortcut-prompt`, which adds the installer dialog with the
// "desktop shortcut" and "Start menu shortcut" checkboxes. It is passed as a free argument to the .msi tasks only:
// jpackage on macOS rejects every `--win-*` option, so the .dmg task must not get it.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Msi) {
        freeArgs.add("--win-shortcut-prompt")
    }
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
            modules("java.instrument", "java.security.jgss", "java.sql", "java.xml.crypto", "jdk.unsupported")

            windows {
                // Must never change: a new .msi replaces the installed one only when this UUID matches.
                upgradeUuid = "ac927098-2ae4-4dac-9c2d-a8dc800bc8fa"
                // Both are on by default; the installer dialog (`--win-shortcut-prompt`, set above) lets the user decline.
                shortcut = true
                menu = true
                menuGroup = "Slav"
                perUserInstall = true
                iconFile.set(iconsDir.file("Slav.ico"))
            }
            macOS {
                bundleID = "io.github.laalka.slav"
                iconFile.set(iconsDir.file("Slav.icns"))
            }
            linux {
                iconFile.set(iconsDir.file("Slav.png"))
            }
        }
    }
}
