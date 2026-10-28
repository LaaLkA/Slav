package io.github.laalka.slav.data

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminPasswordTest {
    @TempDir
    lateinit var dir: Path

    private val paths by lazy { AppPaths(dir.resolve("data")) }
    private lateinit var db: AppDatabase

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths).open()
    }

    @AfterTest
    fun close() = db.close()

    private fun chars(text: String) = text.toCharArray()

    /** A seed of a password of the test's own, with few rounds so the tests stay quick. */
    private fun seed(password: String, salt: Byte = 1) = PasswordSeed.of(chars(password), ByteArray(16) { salt }, iterations = 1_000)

    private fun admin(password: String = SEEDED) = AdminPassword(db, seed(password))

    private fun storedRow() = db.database.adminPasswordQueries.select().executeAsOne()

    @Test
    fun `the password verifies and any other text does not`() {
        val admin = admin()

        assertTrue(admin.verify(chars(SEEDED)))
        assertFalse(admin.verify(chars("")))
        assertFalse(admin.verify(chars(SEEDED.dropLast(1))))
        assertFalse(admin.verify(chars(SEEDED + "x")))
        assertFalse(admin.verify(chars(SEEDED.uppercase())))
        assertFalse(admin.verify(chars(OTHER)))
    }

    @Test
    fun `the arrays handed in are wiped whatever the answer`() {
        val admin = admin()
        val right = chars(SEEDED)
        val wrong = chars(OTHER)
        val current = chars(SEEDED)
        val new = chars(NEW)
        val tooShort = chars("short")
        val wrongCurrent = chars(OTHER)
        val ignored = chars(NEW)

        assertTrue(admin.verify(right))
        assertFalse(admin.verify(wrong))
        assertTrue(admin.change(current, new))
        assertFalse(admin.change(wrongCurrent, ignored))
        assertFalse(admin.change(chars(NEW), tooShort))

        for (array in listOf(right, wrong, current, new, tooShort, wrongCurrent, ignored)) {
            assertTrue(array.all { it == '\u0000' }, "an array still holds ${array.concatToString()}")
        }
    }

    @Test
    fun `changing needs the current password and a new one of at least eight characters`() {
        val admin = admin()

        assertFalse(admin.change(chars(OTHER), chars(NEW)), "wrong current")
        assertFalse(admin.change(chars(SEEDED), chars("1234567")), "seven characters")
        assertFalse(admin.change(chars(SEEDED), chars("")), "empty")
        assertTrue(admin.verify(chars(SEEDED)), "nothing changed")

        assertTrue(admin.change(chars(SEEDED), chars("12345678")), "exactly eight")
        assertTrue(admin.verify(chars("12345678")))
        assertEquals(8, AdminPassword.MIN_LENGTH)
    }

    @Test
    fun `a changed password replaces the old one and each change gets a new salt`() {
        val admin = admin()
        val seeded = storedRow()

        assertTrue(admin.change(chars(SEEDED), chars(NEW)))

        assertFalse(admin.verify(chars(SEEDED)))
        assertTrue(admin.verify(chars(NEW)))
        val first = storedRow()
        assertNotEquals(seeded.salt, first.salt)
        assertNotEquals(seeded.hash, first.hash)
        assertEquals(AdminPassword.ITERATIONS.toLong(), first.iterations)
        assertEquals(16, Base64.getDecoder().decode(first.salt).size)
        assertEquals(AdminPassword.KEY_BYTES, Base64.getDecoder().decode(first.hash).size)

        // The same password again is still a new salt, so a new hash.
        assertTrue(admin.change(chars(NEW), chars(NEW)))
        val second = storedRow()
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertTrue(admin.verify(chars(NEW)))
    }

    @Test
    fun `the seed fills an empty table only, a stored password is never replaced by it`() {
        val admin = admin()
        assertTrue(admin.change(chars(SEEDED), chars(NEW)))

        // Another start of the program with another seed: the password the user set is kept.
        val restarted = AdminPassword(db, seed(OTHER, salt = 2))
        assertTrue(restarted.verify(chars(NEW)))
        assertFalse(restarted.verify(chars(OTHER)))
        assertFalse(restarted.verify(chars(SEEDED)))
        assertEquals(1, rows())
    }

    @Test
    fun `the password survives closing and reopening the database`() {
        assertTrue(admin().change(chars(SEEDED), chars(NEW)))
        db.close()

        db = DatabaseFactory(paths).open()

        assertTrue(admin().verify(chars(NEW)))
        assertFalse(admin().verify(chars(SEEDED)))
    }

    @Test
    fun `the stored record is a salted hash, not the password`() {
        val admin = admin()
        assertTrue(admin.change(chars(SEEDED), chars(NEW)))

        val row = storedRow()
        assertFalse(NEW in row.salt + row.hash)
        assertFalse(SEEDED in row.salt + row.hash)
    }

    @Test
    fun `the settings export does not carry the password`() {
        val admin = admin()
        assertTrue(admin.change(chars(SEEDED), chars(NEW)))
        val row = storedRow()
        val target = dir.resolve("settings.json")

        SettingsTransfer(SettingsRepository(db), paths, appVersion = "test").export(target)

        val text = target.readText()
        assertFalse(row.hash in text)
        assertFalse(row.salt in text)
        assertFalse(NEW in text)
    }

    @Test
    fun `the derivation is PBKDF2 with HMAC-SHA256`() {
        // Known answers for the password "password" and the salt "salt" (RFC 7914 section 11 and the usual vectors).
        fun derived(rounds: Int) = AdminPassword.derive(chars("password"), "salt".toByteArray(), rounds).toHex()

        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", derived(1))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", derived(4096))
    }

    @Test
    fun `the seed of the program is a 16 byte salt and a 32 byte hash of 210000 rounds`() {
        val production = PasswordSeed.PRODUCTION
        val decoder = Base64.getDecoder()

        assertEquals(16, decoder.decode(production.salt).size)
        assertEquals(AdminPassword.KEY_BYTES, decoder.decode(production.hash).size)
        assertEquals(210_000, production.iterations)
        assertEquals(production.iterations, AdminPassword.ITERATIONS)
        // Never the password a person would try first; the real one is not in the program.
        val admin = AdminPassword(db, production)
        listOf("admin", "password", "12345678", "slav").forEach { assertFalse(admin.verify(chars(it)), it) }
    }

    @Test
    fun `a seed made by PasswordSeed matches what verify derives`() {
        val made = seed(SEEDED, salt = 7)
        val decoder = Base64.getDecoder()

        assertContentEquals(
            AdminPassword.derive(chars(SEEDED), decoder.decode(made.salt), made.iterations),
            decoder.decode(made.hash),
        )
    }

    private fun rows(): Int = db.database.adminPasswordQueries.select().executeAsList().size

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEEDED = "tester-seed-1"
        const val OTHER = "tester-other-2"
        const val NEW = "tester-new-3!"
    }
}
