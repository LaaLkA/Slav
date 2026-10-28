package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.db.SlavDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first migration (1.sqm, schema version 1 → 2). The database it starts from is the file the
 * SQLDelight plugin made from the schema before the change (databases/1.db, the one it also replays
 * the migrations on at build time), with the rows a program of that version stored.
 */
class SchemaMigrationTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val paths by lazy { AppPaths(dir.resolve("data")) }
    private val book = PriceBook.default().copy(paintingPerM2 = dec("333"))

    private fun <T> Connection.query(sql: String, read: (java.sql.ResultSet) -> T): T =
        createStatement().use { statement -> statement.executeQuery(sql).use(read) }

    private fun userVersion(file: Path): Long =
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.query("PRAGMA user_version") { rows -> rows.next(); rows.getLong(1) } }

    private fun calculationColumns(file: Path): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.query("SELECT name FROM pragma_table_info('calculation')") { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    /** Writes a calculation row the way the program of schema version 1 did: no source_id column exists yet. */
    private fun Connection.insertV1(id: UUID, status: CalculationStatus, name: String, quantity: Int, priced: Boolean) {
        val input = sheetInput(name, quantity)
        val result = if (priced) book.priced(input) else null
        prepareStatement(
            "INSERT INTO calculation(id, name, name_search, status, quantity, unit_price, created_at, updated_at, " +
                "snapshot_at, input_json, snapshot_json, result_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id.toString())
            statement.setString(2, name)
            statement.setString(3, name.lowercase())
            statement.setString(4, status.name)
            statement.setLong(5, quantity.toLong())
            statement.setString(6, result?.unitPrice?.amount?.toPlainString())
            statement.setLong(7, clock.millis())
            statement.setLong(8, clock.millis() + 1_000)
            if (priced) statement.setLong(9, clock.millis()) else statement.setObject(9, null)
            statement.setString(10, storageJson.encodeToString(io.github.laalka.slav.core.CalculationInput.serializer(), input))
            statement.setString(11, if (priced) storageJson.encodeToString(PriceBook.serializer(), book) else null)
            statement.setString(
                12,
                result?.let { storageJson.encodeToString(StoredResult.serializer(), StoredResult.of(it)) },
            )
            statement.executeUpdate()
        }
    }

    @Test
    fun `a database written by schema version 1 opens with its rows intact`() {
        assertEquals(2L, SlavDatabase.Schema.version)
        paths.root.createDirectories()
        val snapshot = Path(checkNotNull(System.getProperty("slav.schemaSnapshots"))).resolve("1.db")
        snapshot.copyTo(paths.databaseFile)
        val calculated = UUID.randomUUID()
        val sent = UUID.randomUUID()
        val draft = UUID.randomUUID()
        DriverManager.getConnection("jdbc:sqlite:${paths.databaseFile}").use { connection ->
            connection.insertV1(calculated, CalculationStatus.CALCULATED, "Кронштейн", quantity = 3, priced = true)
            connection.insertV1(sent, CalculationStatus.SENT, "Опора", quantity = 1, priced = true)
            connection.insertV1(draft, CalculationStatus.DRAFT, "Рама", quantity = 2, priced = false)
            connection.prepareStatement("INSERT INTO settings(id, json, updated_at) VALUES (1, ?, ?)").use { statement ->
                statement.setString(1, storageJson.encodeToString(PriceBook.serializer(), book))
                statement.setLong(2, clock.millis())
                statement.executeUpdate()
            }
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }
        // Not yet migrated: the old layout, the old version.
        assertEquals(1L, userVersion(paths.databaseFile))
        assertFalse("source_id" in calculationColumns(paths.databaseFile))

        val backup = DatabaseFactory(paths, clock).open().use { db ->
            val calculations = CalculationRepository(db, clock)

            val stored = assertNotNull(calculations.get(calculated))
            assertEquals(CalculationStatus.CALCULATED, stored.status)
            assertEquals(sheetInput("Кронштейн", 3), stored.input)
            assertEquals(book, stored.snapshot)
            assertEquals(book.priced(sheetInput("Кронштейн", 3)), stored.result)
            assertEquals(clock.now, stored.snapshotAt)
            assertNull(stored.sourceId)
            assertEquals(CalculationStatus.SENT, assertNotNull(calculations.get(sent)).status)
            val unfinished = assertNotNull(calculations.get(draft))
            assertEquals(CalculationStatus.DRAFT, unfinished.status)
            assertNull(unfinished.result)
            assertNull(unfinished.snapshot)
            assertEquals(setOf("Кронштейн", "Опора", "Рама"), calculations.list().map { it.name }.toSet())
            assertEquals(book, SettingsRepository(db, clock).load())

            // The new column and the new table are usable on the migrated database.
            val revision = calculations.createRevisionDraft(calculated)
            assertEquals(calculated, assertNotNull(calculations.get(revision)).sourceId)
            assertEquals(listOf(revision), calculations.revisionsOf(calculated).map { it.id })
            assertTrue(AdminPassword(db, PasswordSeed.of("migrated-pass".toCharArray(), ByteArray(16), 1_000)).verify("migrated-pass".toCharArray()))
            assertNotNull(db.startupBackup)
        }

        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
        assertTrue("source_id" in calculationColumns(paths.databaseFile))
        // The copy taken before migrating is the old database: schema 1, three calculations.
        assertEquals(1L, userVersion(backup))
        assertEquals(1, paths.backupsDir.listDirectoryEntries().size)
    }

    @Test
    fun `a new database has the current schema and the same columns as a migrated one`() {
        DatabaseFactory(paths, clock).open().use { }
        val fresh = calculationColumns(paths.databaseFile)

        val migratedPaths = AppPaths(dir.resolve("migrated"))
        migratedPaths.root.createDirectories()
        Path(checkNotNull(System.getProperty("slav.schemaSnapshots"))).resolve("1.db").copyTo(migratedPaths.databaseFile)
        DriverManager.getConnection("jdbc:sqlite:${migratedPaths.databaseFile}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }
        DatabaseFactory(migratedPaths, clock).open().use { }

        assertEquals(fresh, calculationColumns(migratedPaths.databaseFile))
        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
    }
}
