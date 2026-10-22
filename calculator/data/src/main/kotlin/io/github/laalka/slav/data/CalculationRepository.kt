package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.db.Calculation
import io.github.laalka.slav.data.db.SelectSummaries
import java.math.BigDecimal
import java.text.Collator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** Statuses of spec 8.1 in their workflow order, which is also the order the list sorts them in. */
enum class CalculationStatus(val title: String) {
    DRAFT("Черновик"),
    CALCULATED("Рассчитан"),
    SENT("Отправлен"),
    IN_WORK("В работе"),
    ARCHIVED("Архив"),
    ;

    companion object {
        /** Set by the user by hand; the program sets [DRAFT] and [CALCULATED]. */
        val MANUAL: Set<CalculationStatus> = setOf(SENT, IN_WORK, ARCHIVED)
    }
}

/**
 * A saved calculation. A draft has no [result]; every other status has one, with the [snapshot]
 * of the prices it was calculated on (spec 8.4).
 */
data class StoredCalculation(
    val id: UUID,
    val status: CalculationStatus,
    val input: CalculationInput,
    /**
     * The prices of the last calculation; null until the first one and for a copy, which takes
     * the current prices (spec 8.3). A draft saved over a calculated one keeps it, so finishing
     * the edit recalculates on the same prices.
     */
    val snapshot: PriceBook?,
    /** When [snapshot] was taken, the «дата снимка цен» of the card. */
    val snapshotAt: Instant?,
    val result: CalcResult?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val name: String get() = input.name
    val quantity: Int get() = input.quantity
    val unitPrice: Money? get() = result?.unitPrice
}

/** A row of the calculation list (spec 8.1). */
data class CalculationSummary(
    val id: UUID,
    val name: String,
    val status: CalculationStatus,
    val quantity: Int,
    /** Null for a draft. */
    val unitPrice: Money?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val total: Money? get() = unitPrice?.times(quantity)
}

enum class CalculationSortColumn { NAME, UPDATED_AT, STATUS, UNIT_PRICE, QUANTITY, TOTAL }

/** Drafts have no price and come last when sorting by price or total, in either direction. */
data class CalculationSort(val column: CalculationSortColumn, val descending: Boolean) {
    companion object {
        /** Recently changed first. */
        val DEFAULT = CalculationSort(CalculationSortColumn.UPDATED_AT, descending = true)
    }
}

/** Calculations (spec 7.2, 8). Dates of the list filter are taken in the zone of [clock]. */
class CalculationRepository(database: AppDatabase, private val clock: Clock = Clock.systemDefaultZone()) {
    private val queries = database.database.calculationQueries

    /**
     * Saves [input] as a draft (spec 7.2): a new one when [id] is null, otherwise over the
     * calculation [id], which loses its price and result but keeps its snapshot.
     */
    fun saveDraft(input: CalculationInput, id: UUID? = null): UUID {
        val key = id ?: UUID.randomUUID()
        val now = clock.millis()
        val inputJson = encode(input)
        queries.transaction {
            val updated = queries.updateDraft(
                name = input.name,
                nameSearch = searchForm(input.name),
                status = CalculationStatus.DRAFT.name,
                quantity = input.quantity.toLong(),
                updatedAt = now,
                inputJson = inputJson,
                id = key.toString(),
            ).value
            if (updated == 0L) {
                insert(key, input, inputJson, CalculationStatus.DRAFT, now, priced = null)
            }
        }
        return key
    }

    /**
     * Saves a successful calculation (spec 7.1, 8.3) with the prices it used: a new one when [id]
     * is null, otherwise over the calculation [id]. The status becomes «Рассчитан».
     */
    fun saveCalculated(input: CalculationInput, snapshot: PriceBook, result: CalcResult, id: UUID? = null): UUID {
        val key = id ?: UUID.randomUUID()
        val now = clock.millis()
        val inputJson = encode(input)
        val priced = Priced(
            unitPrice = result.unitPrice.amount.toPlainString(),
            snapshotJson = storageJson.encodeToString(PriceBook.serializer(), snapshot),
            resultJson = storageJson.encodeToString(StoredResult.serializer(), StoredResult.of(result)),
        )
        queries.transaction {
            val updated = queries.updateCalculated(
                name = input.name,
                nameSearch = searchForm(input.name),
                status = CalculationStatus.CALCULATED.name,
                quantity = input.quantity.toLong(),
                unitPrice = priced.unitPrice,
                updatedAt = now,
                snapshotAt = now,
                inputJson = inputJson,
                snapshotJson = priced.snapshotJson,
                resultJson = priced.resultJson,
                id = key.toString(),
            ).value
            if (updated == 0L) {
                insert(key, input, inputJson, CalculationStatus.CALCULATED, now, priced)
            }
        }
        return key
    }

    fun get(id: UUID): StoredCalculation? = queries.selectById(id.toString()).executeAsOneOrNull()?.toStored()

    /**
     * The list of spec 8.1. [query] matches a part of the name in any letter case, Cyrillic
     * included; an empty [statuses] means every status; [from] and [to] bound the date of the
     * last change, both days inclusive.
     */
    fun list(
        query: String = "",
        statuses: Set<CalculationStatus> = emptySet(),
        from: LocalDate? = null,
        to: LocalDate? = null,
        sort: CalculationSort = CalculationSort.DEFAULT,
    ): List<CalculationSummary> {
        val zone = clock.zone
        return queries.selectSummaries(
            search = searchForm(query.trim()),
            statuses = statuses.ifEmpty { CalculationStatus.entries }.map { it.name },
            fromMillis = from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            untilMillis = to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        ).executeAsList()
            .map { it.toSummary() }
            .sortedWith(comparator(sort))
    }

    /** A new draft with the input of [id] and no snapshot, so it is priced on the current settings (spec 8.3). */
    fun copy(id: UUID): UUID {
        val source = existing(id)
        val key = UUID.randomUUID()
        insert(key, source.input, encode(source.input), CalculationStatus.DRAFT, clock.millis(), priced = null)
        return key
    }

    /** Does nothing when [id] is already gone. */
    fun delete(id: UUID) {
        queries.delete(id.toString())
    }

    /** Sets one of the [CalculationStatus.MANUAL] statuses; a draft has no price and cannot get one. */
    fun setStatus(id: UUID, status: CalculationStatus) {
        require(status in CalculationStatus.MANUAL) { "Status $status is set by the program, not by hand" }
        queries.transaction {
            val current = existing(id)
            check(current.status != CalculationStatus.DRAFT) { "Draft $id has no price and cannot be marked $status" }
            queries.updateStatus(status.name, clock.millis(), id.toString())
        }
    }

    /** Changes the run size on the card: the total follows, the unit price and the snapshot stay (spec 8.2). */
    fun setQuantity(id: UUID, quantity: Int) {
        require(quantity > 0) { "Quantity must be positive, got $quantity" }
        queries.transaction {
            val input = existing(id).input.copy(quantity = quantity)
            queries.updateQuantity(quantity.toLong(), encode(input), clock.millis(), id.toString())
        }
    }

    private fun existing(id: UUID) = get(id) ?: throw NoSuchElementException("No calculation $id")

    private fun insert(id: UUID, input: CalculationInput, inputJson: String, status: CalculationStatus, now: Long, priced: Priced?) {
        queries.insert(
            id = id.toString(),
            name = input.name,
            nameSearch = searchForm(input.name),
            status = status.name,
            quantity = input.quantity.toLong(),
            unitPrice = priced?.unitPrice,
            createdAt = now,
            updatedAt = now,
            snapshotAt = priced?.let { now },
            inputJson = inputJson,
            snapshotJson = priced?.snapshotJson,
            resultJson = priced?.resultJson,
        )
    }

    private class Priced(val unitPrice: String, val snapshotJson: String, val resultJson: String)

    private companion object {
        // Sorting is done here, not in SQL: names need Russian collation and prices are decimal strings.
        val nameCollator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }

        fun encode(input: CalculationInput) = storageJson.encodeToString(CalculationInput.serializer(), input)

        /** SQLite folds case for ASCII only; the name and the query are both lowercased here instead. */
        fun searchForm(text: String) = text.lowercase()

        fun Calculation.toStored() = StoredCalculation(
            id = UUID.fromString(id),
            status = CalculationStatus.valueOf(status),
            input = storageJson.decodeFromString(CalculationInput.serializer(), input_json),
            snapshot = snapshot_json?.let { storageJson.decodeFromString(PriceBook.serializer(), it) },
            snapshotAt = snapshot_at?.let(Instant::ofEpochMilli),
            result = result_json?.let { storageJson.decodeFromString(StoredResult.serializer(), it).toResult() },
            createdAt = Instant.ofEpochMilli(created_at),
            updatedAt = Instant.ofEpochMilli(updated_at),
        )

        fun SelectSummaries.toSummary() = CalculationSummary(
            id = UUID.fromString(id),
            name = name,
            status = CalculationStatus.valueOf(status),
            quantity = quantity.toInt(),
            unitPrice = unit_price?.let { Money(BigDecimal(it)) },
            createdAt = Instant.ofEpochMilli(created_at),
            updatedAt = Instant.ofEpochMilli(updated_at),
        )

        fun comparator(sort: CalculationSort): Comparator<CalculationSummary> {
            fun <T : Comparable<T>> by(selector: (CalculationSummary) -> T?): Comparator<CalculationSummary> {
                val values: Comparator<T> = if (sort.descending) reverseOrder() else naturalOrder()
                return compareBy(nullsLast(values), selector)
            }
            val primary = when (sort.column) {
                CalculationSortColumn.NAME ->
                    if (sort.descending) compareBy(nameCollator.reversed()) { it.name } else compareBy(nameCollator) { it.name }
                CalculationSortColumn.UPDATED_AT -> by { it.updatedAt }
                CalculationSortColumn.STATUS -> by { it.status }
                CalculationSortColumn.UNIT_PRICE -> by { it.unitPrice }
                CalculationSortColumn.QUANTITY -> by { it.quantity }
                CalculationSortColumn.TOTAL -> by { it.total }
            }
            // Ties: most recently changed first, then by id, so the order is stable between reloads.
            return primary.thenByDescending { it.updatedAt }.thenBy { it.id }
        }
    }
}
