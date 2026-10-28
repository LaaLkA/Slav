package io.github.laalka.slav.data

import java.text.Collator
import java.util.Locale

/** SQLite folds case for ASCII only; a stored name and a search text are both lowercased here instead. */
internal fun searchForm(text: String): String = text.lowercase()

/** Orders names the Russian way, letter case aside; sorting is done in Kotlin, not in SQL. */
internal val nameCollator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
