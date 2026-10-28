package io.github.laalka.slav.data

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path

/**
 * Where the application keeps its data (spec 11): the database, the backups folder and the
 * attached drawings under [root]. Nothing is created until the database is opened. Tests pass a
 * temporary [root].
 */
class AppPaths(val root: Path) {
    val databaseFile: Path get() = root.resolve("slav.db")

    /** Startup copies of the database and the settings saved before a full import replace. */
    val backupsDir: Path get() = root.resolve("backups")

    /** The drawings attached to calculations, a folder per calculation; created with the first file. */
    val attachmentsDir: Path get() = root.resolve("attachments")

    companion object {
        /** The per-user data folder of the current OS; uninstalling or updating the app leaves it in place. */
        fun default(): AppPaths =
            AppPaths(defaultRoot(System.getProperty("os.name"), System.getProperty("user.home"), System.getenv("APPDATA")))

        /** Windows %APPDATA%\Slav, macOS ~/Library/Application Support/Slav, otherwise ~/.local/share/Slav. */
        internal fun defaultRoot(osName: String, userHome: String, appData: String?): Path {
            val base = when {
                osName.startsWith("Windows") -> appData?.let(::Path) ?: Path(userHome, "AppData", "Roaming")
                osName.startsWith("Mac") -> Path(userHome, "Library", "Application Support")
                else -> Path(userHome, ".local", "share")
            }
            return base.resolve("Slav")
        }
    }
}

// UTC, so the names of backup files sort in the order they were made, daylight saving included.
private val fileStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'").withZone(ZoneOffset.UTC)

/** A timestamp usable in a file name on every OS: 2026-10-22T09-15-30-123Z. */
internal fun fileStamp(instant: Instant): String = fileStampFormat.format(instant)
