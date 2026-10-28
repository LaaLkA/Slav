package io.github.laalka.slav.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A password hash as it is stored: PBKDF2WithHmacSHA256 with [iterations] rounds. [salt] and [hash]
 * are Base64, the hash is [AdminPassword.KEY_BYTES] bytes.
 */
class PasswordSeed(val salt: String, val hash: String, val iterations: Int) {
    companion object {
        /** The hash of the password a new installation starts with; the password itself is not kept anywhere in the program. */
        val PRODUCTION = PasswordSeed(
            salt = "5NM/72I7PSnUhfickec+kQ==",
            hash = "o0VvKlTP9PsJTrWAZLj7foEVLckOFmFf7pg9eg4GqmM=",
            iterations = 210_000,
        )

        /** The seed of [password], for a test that needs a password of its own; [password] is wiped. */
        fun of(password: CharArray, salt: ByteArray, iterations: Int): PasswordSeed {
            val encoder = Base64.getEncoder()
            return PasswordSeed(encoder.encodeToString(salt), encoder.encodeToString(AdminPassword.derive(password, salt, iterations)), iterations)
        }
    }
}

/**
 * The administrator password that guards deleting a calculation. It is stored as a salted PBKDF2
 * hash in the database; an empty table is first filled with [seed]. The password is a [CharArray]
 * and every function wipes the arrays it is given before it returns, so pass a copy you do not
 * need again. The password is not part of the settings, their export or any log.
 */
class AdminPassword(database: AppDatabase, seed: PasswordSeed) {
    private val queries = database.database.adminPasswordQueries

    init {
        queries.seed(seed.salt, seed.hash, seed.iterations.toLong())
    }

    /** Whether [password] is the administrator password. */
    fun verify(password: CharArray): Boolean {
        try {
            val stored = queries.select().executeAsOne()
            val derived = derive(password, decode(stored.salt), stored.iterations.toInt())
            return try {
                MessageDigest.isEqual(derived, decode(stored.hash))
            } finally {
                derived.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Replaces the password with [new] under a new salt. False, and nothing changed, when [current]
     * is not the password or [new] is shorter than [MIN_LENGTH].
     */
    fun change(current: CharArray, new: CharArray): Boolean {
        try {
            if (new.size < MIN_LENGTH || !verify(current)) return false
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val derived = derive(new, salt, ITERATIONS)
            try {
                val encoder = Base64.getEncoder()
                queries.replace(encoder.encodeToString(salt), encoder.encodeToString(derived), ITERATIONS.toLong())
            } finally {
                derived.fill(0)
            }
            return true
        } finally {
            current.fill('\u0000')
            new.fill('\u0000')
        }
    }

    companion object {
        const val MIN_LENGTH = 8
        const val KEY_BYTES = 32
        const val ITERATIONS = 210_000
        private const val SALT_BYTES = 16

        private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

        /** PBKDF2WithHmacSHA256 of [password], wiped afterwards. */
        internal fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
                password.fill('\u0000')
            }
        }
    }
}
