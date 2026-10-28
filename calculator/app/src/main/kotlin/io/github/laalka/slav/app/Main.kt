package io.github.laalka.slav.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.laalka.slav.app.theme.SlavTheme
import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.AppPaths
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.DatabaseFactory
import io.github.laalka.slav.data.PasswordSeed
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.SettingsTransfer
import java.awt.Desktop
import java.awt.Dimension
import java.awt.desktop.QuitStrategy
import kotlin.io.path.Path

/**
 * The composition root: the database of [paths] and everything built on it, created once per
 * start. Opening the database backs up the existing one first (spec 11). [adminSeed] is the
 * password hash a new database starts with; a test passes the hash of a password of its own.
 */
class Deps(
    paths: AppPaths,
    appVersion: String,
    adminSeed: PasswordSeed = PasswordSeed.PRODUCTION,
    /** How «Открыть» shows a drawing; a test passes one that records the file instead of starting a program. */
    val fileOpener: FileOpener = DesktopFileOpener,
) : AutoCloseable {
    private val database = DatabaseFactory(paths).open()
    val settings = SettingsRepository(database)

    // The calculations copy and delete the drawings with themselves, so they share this one.
    val attachments = AttachmentRepository(database)
    val calculations = CalculationRepository(database, attachments = attachments)
    val adminPassword = AdminPassword(database, adminSeed)
    val settingsTransfer = SettingsTransfer(settings, paths, appVersion)
    val preferences = AppPreferences(paths.root.resolve("preferences.properties"))

    override fun close() = database.close()
}

fun main() {
    val deps = Deps(dataPaths(), System.getProperty("slav.appVersion") ?: DEVELOPMENT_VERSION)
    // Cmd+Q on macOS then closes the window like its close button, so the database is closed the same way.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
        Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
    }
    application {
        val navigator = rememberNavigator(deps)
        Window(
            // An edited calculation may first ask whether to keep the edit (spec 8.3).
            onCloseRequest = {
                navigator.requestExit {
                    deps.close()
                    exitApplication()
                }
            },
            title = "Slav — расчёт стоимости",
            state = rememberWindowState(size = DpSize(MIN_WIDTH.dp, MIN_HEIGHT.dp)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
            val themeMode by deps.preferences.themeMode.collectAsState()
            SlavTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                App(deps, remember { NativeFilePicker(window) }, navigator)
            }
        }
    }
}

/** SLAV_DATA_DIR runs the program on another data folder, e.g. a trial run on an empty database. */
private fun dataPaths(): AppPaths =
    System.getenv("SLAV_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { AppPaths(Path(it)) } ?: AppPaths.default()

// The build passes the real version as -Dslav.appVersion; only a launch from an IDE goes without it.
private const val DEVELOPMENT_VERSION = "dev"

// Spec 11: the smallest window the layout is designed for.
private const val MIN_WIDTH = 1280
private const val MIN_HEIGHT = 800
