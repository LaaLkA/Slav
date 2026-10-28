package io.github.laalka.slav.data

import io.github.laalka.slav.core.Part
import io.github.laalka.slav.data.db.Part_template as TemplateRow
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A ready part of the library. [part] is a copy: adding it to a calculation copies it again, and
 * neither the calculation nor a later [PartTemplateRepository.save] over this name changes the other.
 */
data class PartTemplate(
    val id: UUID,
    /** The name as typed, trimmed; unique among templates in any letter case. */
    val name: String,
    val part: Part,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** What came of a [PartTemplateRepository.save] or [PartTemplateRepository.rename]. */
sealed interface TemplateWriteResult {
    data class Written(val template: PartTemplate) : TemplateWriteResult

    /** Another template has this name, compared trimmed and in any letter case; nothing was written. */
    data class NameTaken(val existing: PartTemplate) : TemplateWriteResult
}

/**
 * The library of ready parts («Библиотека деталей»): a part saved once is added to any product.
 * Names are unique after trimming and in any letter case, Cyrillic included. The part is stored as
 * JSON in the form of the core model; a part written by an older program is brought to the
 * current model on every read, as a calculation's input is (InputJsonUpgrade.kt).
 */
class PartTemplateRepository(database: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val queries = database.database.partTemplateQueries

    /**
     * Saves [part] under [name]. When a template of that name exists it is kept as it is and
     * [TemplateWriteResult.NameTaken] is returned, unless [overwrite] is set: then its part is
     * replaced (its id, name and creation date stay). Throws when [name] is blank.
     */
    fun save(name: String, part: Part, overwrite: Boolean = false): TemplateWriteResult {
        val title = cleanName(name)
        val search = searchForm(title)
        val partJson = encode(part)
        val now = clock.millis()
        return queries.transactionWithResult {
            val existing = queries.selectByNameSearch(search).executeAsOneOrNull()
            when {
                existing == null -> {
                    val id = UUID.randomUUID()
                    queries.insert(id.toString(), title, search, partJson, createdAt = now, updatedAt = now)
                    TemplateWriteResult.Written(PartTemplate(id, title, part, Instant.ofEpochMilli(now), Instant.ofEpochMilli(now)))
                }
                !overwrite -> TemplateWriteResult.NameTaken(existing.toTemplate())
                else -> {
                    queries.overwrite(partJson, updatedAt = now, id = existing.id)
                    TemplateWriteResult.Written(
                        PartTemplate(UUID.fromString(existing.id), existing.name, part, Instant.ofEpochMilli(existing.created_at), Instant.ofEpochMilli(now)),
                    )
                }
            }
        }
    }

    /**
     * The templates whose name contains [search] in any letter case, Cyrillic included; every
     * template for a blank one. In the order of the names, the Russian way.
     */
    fun list(search: String = ""): List<PartTemplate> =
        queries.selectMatching(searchForm(search.trim())).executeAsList()
            .map { it.toTemplate() }
            .sortedWith(compareBy(nameCollator) { it.name })

    fun get(id: UUID): PartTemplate? = queries.selectById(id.toString()).executeAsOneOrNull()?.toTemplate()

    /**
     * Gives the template [id] a new name, keeping its part. [TemplateWriteResult.NameTaken] when
     * another template has that name; a name that differs from the old one in letter case only is
     * the same template's own and is accepted. Throws when [name] is blank or [id] is gone.
     */
    fun rename(id: UUID, name: String): TemplateWriteResult {
        val title = cleanName(name)
        val search = searchForm(title)
        val now = clock.millis()
        return queries.transactionWithResult {
            val current = queries.selectById(id.toString()).executeAsOneOrNull() ?: throw NoSuchElementException("No part template $id")
            val taken = queries.selectByNameSearch(search).executeAsOneOrNull()
            if (taken != null && taken.id != current.id) {
                TemplateWriteResult.NameTaken(taken.toTemplate())
            } else {
                queries.rename(title, search, updatedAt = now, id = current.id)
                TemplateWriteResult.Written(current.toTemplate().copy(name = title, updatedAt = Instant.ofEpochMilli(now)))
            }
        }
    }

    /** Removes the template [id]; does nothing when it is already gone. Calculations that took the part are not touched. */
    fun delete(id: UUID) {
        queries.delete(id.toString())
    }

    private fun cleanName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "A part template needs a name" } }

    private companion object {
        fun encode(part: Part): String = storageJson.encodeToString(Part.serializer(), part)

        // Rows written by an older program are brought to the current model on every read (InputJsonUpgrade.kt).
        fun decode(text: String): Part =
            storageJson.decodeFromJsonElement(Part.serializer(), upgradePartJson(storageJson.parseToJsonElement(text)))

        fun TemplateRow.toTemplate() = PartTemplate(
            id = UUID.fromString(id),
            name = name,
            part = decode(part_json),
            createdAt = Instant.ofEpochMilli(created_at),
            updatedAt = Instant.ofEpochMilli(updated_at),
        )
    }
}
