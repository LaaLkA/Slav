package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import java.time.Clock

/** The settings of spec 4, stored as one row. */
class SettingsRepository(database: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val queries = database.database.settingsQueries

    /** The current settings; on the first run [PriceBook.default] is stored and returned. */
    fun load(): PriceBook = queries.transactionWithResult {
        queries.select().executeAsOneOrNull()?.let { storageJson.decodeFromString(PriceBook.serializer(), it) }
            ?: PriceBook.default().also(::save)
    }

    fun save(book: PriceBook) {
        queries.upsert(storageJson.encodeToString(PriceBook.serializer(), book), clock.millis())
    }
}
