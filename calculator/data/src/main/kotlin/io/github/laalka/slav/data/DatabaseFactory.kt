package io.github.laalka.slav.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.laalka.slav.data.db.SlavDatabase
import java.nio.file.Path
import java.time.Clock
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Opens the database of [paths] once per application start (spec 11). */
class DatabaseFactory(private val paths: AppPaths, private val clock: Clock = Clock.systemUTC()) {
    /**
     * Backs up an existing database into the backups folder, keeping the newest [MAX_BACKUPS],
     * then opens it. The driver creates the schema in a new database and runs the migrations
     * (.sqm files) on an older one; the schema version is kept in PRAGMA user_version.
     */
    fun open(): AppDatabase {
        paths.root.createDirectories()
        val backup = if (paths.databaseFile.exists()) backUp() else null
        val driver = JdbcSqliteDriver(jdbcUrl(paths.databaseFile), Properties(), SlavDatabase.Schema)
        return AppDatabase(SlavDatabase(driver), driver, backup)
    }

    // Taken before migrating, so a failed migration leaves the previous version restorable.
    private fun backUp(): Path {
        val dir = paths.backupsDir.createDirectories()
        val target = dir.resolve("slav-${fileStamp(clock.instant())}.db")
        // VACUUM INTO rather than a file copy: SQLite first rolls back a journal left by a crash,
        // so the copy is always a consistent database, user_version included.
        JdbcSqliteDriver(jdbcUrl(paths.databaseFile), Properties()).use { driver ->
            driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, target.toString()) }
        }
        // The new copy is kept even when its name sorts first, as after the system clock was set back.
        dir.listDirectoryEntries("slav-*.db")
            .filter { it != target }
            .sortedByDescending { it.name }
            .drop(MAX_BACKUPS - 1)
            .forEach { it.deleteIfExists() }
        return target
    }

    companion object {
        const val MAX_BACKUPS = 10

        private fun jdbcUrl(file: Path) = "jdbc:sqlite:$file"
    }
}

/** The open database; repositories share it. Close it when the application exits. */
class AppDatabase internal constructor(
    internal val database: SlavDatabase,
    private val driver: SqlDriver,
    /** The copy made at this start; null on the first start, when there was nothing to back up. */
    val startupBackup: Path?,
) : AutoCloseable {
    override fun close() = driver.close()
}
