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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The migrations (1.sqm, schema version 1 → 2; 2.sqm, 2 → 3; 3.sqm, 3 → 4). The database each starts
 * from is the file the SQLDelight plugin made from the schema before the change (databases/1.db,
 * 2.db, 3.db, the ones it also replays the migrations on at build time), with the rows a program of
 * that version stored.
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

    private fun names(file: Path, sql: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.query(sql) { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun columns(file: Path, table: String): List<String> = names(file, "SELECT name FROM pragma_table_info('$table')")

    private fun tables(file: Path): List<String> = names(file, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    private fun indexes(file: Path, table: String): List<String> =
        names(file, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' ORDER BY name")

    private fun snapshot(name: String): Path = Path(checkNotNull(System.getProperty("slav.schemaSnapshots"))).resolve(name)

    /** Writes a calculation row without the source_id column, which schema version 1 did not have and later ones leave empty. */
    private fun Connection.insertWithoutSource(id: UUID, status: CalculationStatus, name: String, quantity: Int, priced: Boolean) {
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
        assertEquals(4L, SlavDatabase.Schema.version)
        paths.root.createDirectories()
        snapshot("1.db").copyTo(paths.databaseFile)
        val calculated = UUID.randomUUID()
        val sent = UUID.randomUUID()
        val draft = UUID.randomUUID()
        DriverManager.getConnection("jdbc:sqlite:${paths.databaseFile}").use { connection ->
            connection.insertWithoutSource(calculated, CalculationStatus.CALCULATED, "Кронштейн", quantity = 3, priced = true)
            connection.insertWithoutSource(sent, CalculationStatus.SENT, "Опора", quantity = 1, priced = true)
            connection.insertWithoutSource(draft, CalculationStatus.DRAFT, "Рама", quantity = 2, priced = false)
            connection.prepareStatement("INSERT INTO settings(id, json, updated_at) VALUES (1, ?, ?)").use { statement ->
                statement.setString(1, storageJson.encodeToString(PriceBook.serializer(), book))
                statement.setLong(2, clock.millis())
                statement.executeUpdate()
            }
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }
        // Not yet migrated: the old layout, the old version.
        assertEquals(1L, userVersion(paths.databaseFile))
        assertFalse("source_id" in columns(paths.databaseFile, "calculation"))
        assertFalse("attachment" in tables(paths.databaseFile))
        assertFalse("part_template" in tables(paths.databaseFile))

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
        assertTrue("source_id" in columns(paths.databaseFile, "calculation"))
        assertTrue("attachment" in tables(paths.databaseFile))
        assertTrue("part_template" in tables(paths.databaseFile))
        // The copy taken before migrating is the old database: schema 1, three calculations.
        assertEquals(1L, userVersion(backup))
        assertEquals(1, paths.backupsDir.listDirectoryEntries().size)
    }

    @Test
    fun `a database written by schema version 2 gets the attachment table and keeps its rows`() {
        paths.root.createDirectories()
        snapshot("2.db").copyTo(paths.databaseFile)
        val calculated = UUID.randomUUID()
        val revision = UUID.randomUUID()
        DriverManager.getConnection("jdbc:sqlite:${paths.databaseFile}").use { connection ->
            connection.insertWithoutSource(calculated, CalculationStatus.CALCULATED, "Кронштейн", quantity = 3, priced = true)
            connection.insertWithoutSource(revision, CalculationStatus.DRAFT, "Кронштейн", quantity = 3, priced = false)
            connection.createStatement().use { it.execute("UPDATE calculation SET source_id = '$calculated' WHERE id = '$revision'") }
            connection.createStatement().use { it.execute("PRAGMA user_version = 2") }
        }
        assertEquals(2L, userVersion(paths.databaseFile))
        assertFalse("attachment" in tables(paths.databaseFile))

        val backup = DatabaseFactory(paths, clock).open().use { db ->
            val calculations = CalculationRepository(db, clock)
            val attachments = AttachmentRepository(db, clock)

            assertEquals(revision, calculations.revisionsOf(calculated).single().id)
            assertEquals(book.priced(sheetInput("Кронштейн", 3)), assertNotNull(calculations.get(calculated)).result)
            assertEquals(emptyList(), attachments.list(calculated))

            // The new table is usable: a drawing is attached and goes along with a copy.
            val drawing = dir.resolve("plan.pdf").also { it.writeText("%PDF") }
            val added = assertIs<AttachResult.Added>(attachments.add(calculated, drawing)).attachment
            assertEquals(listOf(added), attachments.list(calculated))
            assertEquals(1, attachments.list(calculations.copy(calculated)).size)
            checkNotNull(db.startupBackup)
        }

        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
        // Next to the index SQLite makes for the primary key.
        assertTrue("attachment_calculation_id" in indexes(paths.databaseFile, "attachment"))
        assertTrue("part_template" in tables(paths.databaseFile))
        // The copy taken before migrating is the old database: schema 2, no attachment table.
        assertEquals(2L, userVersion(backup))
        assertFalse("attachment" in tables(backup))
    }

    @Test
    fun `a database written by schema version 3 gets the library of parts and keeps its rows`() {
        paths.root.createDirectories()
        snapshot("3.db").copyTo(paths.databaseFile)
        val calculated = UUID.randomUUID()
        DriverManager.getConnection("jdbc:sqlite:${paths.databaseFile}").use { connection ->
            connection.insertWithoutSource(calculated, CalculationStatus.CALCULATED, "Кронштейн", quantity = 3, priced = true)
            connection.createStatement().use { it.execute("PRAGMA user_version = 3") }
        }
        assertEquals(3L, userVersion(paths.databaseFile))
        assertFalse("part_template" in tables(paths.databaseFile))

        val backup = DatabaseFactory(paths, clock).open().use { db ->
            val calculations = CalculationRepository(db, clock)
            val templates = PartTemplateRepository(db, clock)

            assertEquals(book.priced(sheetInput("Кронштейн", 3)), assertNotNull(calculations.get(calculated)).result)
            assertEquals(emptyList(), templates.list())

            // The new table is usable, and its names are unique in any letter case.
            val part = sheetInput("Кронштейн").parts.single()
            val saved = assertIs<TemplateWriteResult.Written>(templates.save("Кронштейн", part)).template
            assertIs<TemplateWriteResult.NameTaken>(templates.save("КРОНШТЕЙН", part))
            assertEquals(listOf(saved), templates.list("крон"))
            checkNotNull(db.startupBackup)
        }

        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
        assertEquals(listOf("part_template_name_search"), indexes(paths.databaseFile, "part_template").filterNot { it.startsWith("sqlite_autoindex") })
        // The copy taken before migrating is the old database: schema 3, no library of parts.
        assertEquals(3L, userVersion(backup))
        assertFalse("part_template" in tables(backup))
    }

    @Test
    fun `a new database has the current schema and the same columns and indexes as a migrated one`() {
        DatabaseFactory(paths, clock).open().use { }

        listOf("1.db" to 1, "2.db" to 2, "3.db" to 3).forEach { (name, version) ->
            val migratedPaths = AppPaths(dir.resolve("migrated-$version"))
            migratedPaths.root.createDirectories()
            snapshot(name).copyTo(migratedPaths.databaseFile)
            DriverManager.getConnection("jdbc:sqlite:${migratedPaths.databaseFile}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
            }
            DatabaseFactory(migratedPaths, clock).open().use { }

            listOf("calculation", "attachment", "part_template").forEach { table ->
                assertEquals(columns(paths.databaseFile, table), columns(migratedPaths.databaseFile, table), "$table, from schema $version")
                assertEquals(indexes(paths.databaseFile, table), indexes(migratedPaths.databaseFile, table), "$table, from schema $version")
            }
            assertEquals(tables(paths.databaseFile), tables(migratedPaths.databaseFile))
        }
        assertEquals(SlavDatabase.Schema.version, userVersion(paths.databaseFile))
    }
}
