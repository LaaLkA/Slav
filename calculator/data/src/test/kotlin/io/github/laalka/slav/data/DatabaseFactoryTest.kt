package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.db.SlavDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseFactoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()

    // A folder that does not exist yet, as on the first start after installation.
    private val paths by lazy { AppPaths(dir.resolve("Slav")) }

    /** One application start; starts are a minute apart. */
    private fun start(): AppDatabase = DatabaseFactory(paths, clock).open().also { clock.advance(Duration.ofMinutes(1)) }

    @Test
    fun `the first start creates the database with the current schema and backs up nothing`() {
        start().use { db ->
            assertNull(db.startupBackup)
            assertEquals(emptyList(), CalculationRepository(db, clock).list())
        }
        assertTrue(paths.databaseFile.exists())
        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
        assertFalse(paths.backupsDir.exists())
    }

    @Test
    fun `settings and calculations survive closing and reopening`() {
        val book = PriceBook.default().copy(paintingPerM2 = dec("350.50"))
        val input = sheetInput("Кронштейн", quantity = 25)
        val id = start().use { db ->
            SettingsRepository(db, clock).save(book)
            CalculationRepository(db, clock).saveCalculated(input, book, book.priced(input))
        }

        start().use { db ->
            assertEquals(book, SettingsRepository(db, clock).load())
            val stored = assertNotNull(CalculationRepository(db, clock).get(id))
            assertEquals(CalculationStatus.CALCULATED, stored.status)
            assertEquals(input, stored.input)
            assertEquals(book, stored.snapshot)
            assertEquals(book.priced(input), stored.result)
        }
    }

    @Test
    fun `the startup backup is a database that opens with the data`() {
        val id = start().use { db -> CalculationRepository(db, clock).saveDraft(sheetInput("Кронштейн")) }
        val backup = start().use { db -> assertNotNull(db.startupBackup) }
        assertEquals(paths.backupsDir, backup.parent)

        // Restoring a backup is putting it in place of the database file.
        val restored = AppPaths(dir.resolve("restored"))
        backup.copyTo(restored.root.createDirectories().resolve(restored.databaseFile.fileName))
        DatabaseFactory(restored, clock).open().use { db ->
            assertEquals("Кронштейн", CalculationRepository(db, clock).get(id)?.name)
        }
    }

    @Test
    fun `backups rotate keeping the newest ten`() {
        start().close()
        val made = (1..13).map { start().use { db -> assertNotNull(db.startupBackup) } }

        assertEquals(made.takeLast(DatabaseFactory.MAX_BACKUPS).sorted(), paths.backupsDir.listDirectoryEntries().sorted())
    }

    @Test
    fun `the backup of this start is kept after the clock was set back`() {
        repeat(DatabaseFactory.MAX_BACKUPS + 1) { start().close() }
        clock.now = clock.now.minus(Duration.ofDays(365))

        val newest = start().use { db -> assertNotNull(db.startupBackup) }

        val kept = paths.backupsDir.listDirectoryEntries()
        assertEquals(DatabaseFactory.MAX_BACKUPS, kept.size)
        assertTrue(newest in kept)
    }

    private fun userVersion(file: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows -> check(rows.next()); rows.getLong(1) }
            }
        }
}

class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the first load stores the default settings`() {
        val paths = AppPaths(dir)
        DatabaseFactory(paths).open().use { db -> assertEquals(PriceBook.default(), SettingsRepository(db).load()) }
        // Stored, not only returned: the row is there for the next start.
        DriverManager.getConnection("jdbc:sqlite:${paths.databaseFile}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM settings").use { rows -> check(rows.next()); assertEquals(1, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun `saved settings replace the previous ones`() {
        DatabaseFactory(AppPaths(dir)).open().use { db ->
            val repository = SettingsRepository(db)
            val edited = repository.load().renameGrade("Ст3", "Ст3сп")
            repository.save(edited)
            assertEquals(edited, repository.load())
        }
    }
}

class AppPathsTest {
    @Test
    fun `the data folder of each OS (spec 11)`() {
        assertEquals(
            Path("C:\\Users\\m\\AppData\\Roaming").resolve("Slav"),
            AppPaths.defaultRoot("Windows 11", "C:\\Users\\m", appData = "C:\\Users\\m\\AppData\\Roaming"),
        )
        assertEquals(Path("/Users/m/Library/Application Support/Slav"), AppPaths.defaultRoot("Mac OS X", "/Users/m", null))
        assertEquals(Path("/home/m/.local/share/Slav"), AppPaths.defaultRoot("Linux", "/home/m", null))
    }

    @Test
    fun `under test the default folder is inside the build directory, not the user's`() {
        val root = AppPaths.default().root
        assertTrue(root.startsWith(Path(System.getProperty("user.home"))))
        assertTrue(root.toString().contains("${Path("build", "test-home")}"), "user.home is not redirected: $root")
    }
}
