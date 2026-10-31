package io.github.laalka.slav.data

import io.github.laalka.slav.core.CalcResult
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculationInput
import io.github.laalka.slav.core.QuickResult
import io.github.laalka.slav.data.db.Calculation
import io.github.laalka.slav.data.db.SelectSummaries
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
 * What a calculation is: a full one with parts and operations, or the simplified one, a sheet and
 * the laser time. The kind is chosen when the calculation is started and never changes.
 */
enum class CalculationKind(val title: String) {
    FULL("Полный расчёт"),
    QUICK("Упрощённый расчёт"),
}

/** A simplified calculation has no run size: it is one piece, so its total is its unit price. */
internal const val QUICK_RUN_SIZE = 1

/**
 * Thrown when a calculation is written under a name another calculation already has. Names are
 * unique among all calculations, compared without regard to letter case and outer spaces.
 */
class DuplicateNameException(val name: String) : RuntimeException("A calculation named «$name» already exists")

/**
 * A saved calculation of either [kind]: [Full] holds a [CalculationInput] and a [CalcResult],
 * [Quick] a [QuickCalculationInput] and a [QuickResult]. A draft has no result; every other status
 * has one, with the [snapshot] of the prices it was calculated on (spec 8.4).
 */
sealed interface StoredCalculation {
    val id: UUID
    val kind: CalculationKind
    val status: CalculationStatus

    /**
     * The prices of the last calculation; null until the first one and for a copy, which takes
     * the current prices (spec 8.3). A draft saved over a calculated one keeps it, so finishing
     * the edit recalculates on the same prices.
     */
    val snapshot: PriceBook?

    /** When [snapshot] was taken, the «дата снимка цен» of the card. */
    val snapshotAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant

    /** The calculation this one was made from; null for an original and for a plain copy (spec 8.3). */
    val sourceId: UUID?
    val name: String

    /** The customer the calculation is for; empty when not given. */
    val counterparty: String

    /** The run size. A simplified calculation has none and always reports 1. */
    val quantity: Int

    /** The price of one piece, the discount already taken off; null for a draft. */
    val unitPrice: Money?

    /** A draft is not: it has no price to show, export or recalculate. */
    val isPriced: Boolean get() = unitPrice != null

    data class Full(
        override val id: UUID,
        override val status: CalculationStatus,
        val input: CalculationInput,
        override val snapshot: PriceBook?,
        override val snapshotAt: Instant?,
        val result: CalcResult?,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val sourceId: UUID?,
    ) : StoredCalculation {
        override val kind: CalculationKind get() = CalculationKind.FULL
        override val name: String get() = input.name
        override val counterparty: String get() = input.counterparty
        override val quantity: Int get() = input.quantity
        override val unitPrice: Money? get() = result?.unitPrice
    }

    data class Quick(
        override val id: UUID,
        override val status: CalculationStatus,
        val input: QuickCalculationInput,
        override val snapshot: PriceBook?,
        override val snapshotAt: Instant?,
        val result: QuickResult?,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val sourceId: UUID?,
    ) : StoredCalculation {
        override val kind: CalculationKind get() = CalculationKind.QUICK
        override val name: String get() = input.name
        override val counterparty: String get() = input.counterparty

        /**
         * Always 1, whatever [input] holds: a simplified calculation has no run size. A row stored when
         * it still had one, with a run other than 1, shows its total as its unit price from now on.
         */
        override val quantity: Int get() = QUICK_RUN_SIZE
        override val unitPrice: Money? get() = result?.unitPrice
    }
}

/** A row of the calculation list (spec 8.1). */
data class CalculationSummary(
    val id: UUID,
    val name: String,
    /** Empty when not given. */
    val counterparty: String,
    val kind: CalculationKind,
    val status: CalculationStatus,
    /** The run size; 1 for a simplified calculation, which has none. */
    val quantity: Int,
    /** The price of one piece after the discount; null for a draft. */
    val unitPrice: Money?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val total: Money? get() = unitPrice?.times(quantity)
}

enum class CalculationSortColumn { NAME, COUNTERPARTY, UPDATED_AT, STATUS, UNIT_PRICE, QUANTITY, TOTAL }

/** Drafts have no price and come last when sorting by price or total, in either direction. */
data class CalculationSort(val column: CalculationSortColumn, val descending: Boolean) {
    companion object {
        /** Recently changed first. */
        val DEFAULT = CalculationSort(CalculationSortColumn.UPDATED_AT, descending = true)
    }
}

/**
 * Calculations (spec 7.2, 8). Dates of the list filter are taken in the zone of [clock].
 *
 * A calculation that is not a draft is not changed in place, so the trace of a wrong calculation
 * stays: a recalculation or a new run size makes a new row ([createRevision], [createRevisionDraft])
 * that points to its source. Only the workflow status of such a row can be set ([setStatus]);
 * writing its input, price or run size with the other methods throws [IllegalStateException].
 * The one exception is [overwriteCalculated], the owner's «Изменить без создания нового расчёта»,
 * which replaces a calculated row in place. It is not guarded here: the caller must have verified
 * the administrator password ([AdminPassword]) first.
 *
 * The same holds for both kinds of calculation ([CalculationKind]). Saving takes an input and the
 * result of its own kind, and a row is never written with the other kind's input.
 *
 * Names are unique among all calculations, compared by their search form (trimmed, in any letter
 * case): a write that would give a second calculation a name throws [DuplicateNameException]. A blank
 * name is no name, so a draft may have it, any number of them. Names already doubled in a database
 * from before this rule are left as they are, and only a write of one of them is checked, so such a
 * row can be written again once it is renamed. A copy and a revision are named by the program
 * ([nextCopyName], [nextRevisionName]).
 *
 * The drawings of a calculation ([AttachmentRepository]) follow it: a copy or a revision gets
 * copies of its source's files, and deleting the calculation deletes them. That is done here, so
 * no screen has to remember it.
 */
class CalculationRepository(
    database: AppDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val attachments: AttachmentRepository = AttachmentRepository(database, clock),
) {
    private val queries = database.database.calculationQueries

    /**
     * Saves [input] as a draft (spec 7.2): a new one when [id] is null or unknown, otherwise over
     * the draft [id], which keeps its snapshot. Throws when [id] is not a draft, and
     * [DuplicateNameException] when another calculation has the name.
     */
    fun saveDraft(input: CalculationInput, id: UUID? = null): UUID = saveDraft(Content.of(input), id)

    /** [saveDraft] of a simplified calculation; throws as well when [id] is a draft of the other kind. */
    fun saveDraft(input: QuickCalculationInput, id: UUID? = null): UUID = saveDraft(Content.of(input), id)

    private fun saveDraft(content: Content, id: UUID?): UUID {
        val key = id ?: UUID.randomUUID()
        val now = clock.millis()
        queries.transaction {
            requireDraftOrAbsent(key, content.kind)
            requireNameFree(content.name, key)
            val updated = queries.updateDraft(
                name = content.name,
                nameSearch = content.nameSearch,
                counterparty = content.counterparty,
                counterpartySearch = content.counterpartySearch,
                status = CalculationStatus.DRAFT.name,
                quantity = content.quantity.toLong(),
                updatedAt = now,
                inputJson = content.inputJson,
                id = key.toString(),
            ).value
            if (updated == 0L) {
                insert(key, content, CalculationStatus.DRAFT, now, priced = null)
            }
        }
        return key
    }

    /**
     * Saves a successful calculation (spec 7.1, 8.3) with the prices it used: a new one when [id]
     * is null or unknown, otherwise over the draft [id], which keeps its [StoredCalculation.sourceId].
     * The status becomes «Рассчитан». Throws when [id] is not a draft, and [DuplicateNameException]
     * when another calculation has the name. [snapshotAt] is when [snapshot] was taken; now, unless
     * the prices are an older snapshot.
     */
    fun saveCalculated(input: CalculationInput, snapshot: PriceBook, result: CalcResult, id: UUID? = null, snapshotAt: Instant? = null): UUID =
        saveCalculated(Content.of(input), pricing(snapshot, result), id, snapshotAt)

    /** [saveCalculated] of a simplified calculation. */
    fun saveCalculated(input: QuickCalculationInput, snapshot: PriceBook, result: QuickResult, id: UUID? = null, snapshotAt: Instant? = null): UUID =
        saveCalculated(Content.of(input), pricing(snapshot, result), id, snapshotAt)

    private fun saveCalculated(content: Content, priced: Priced, id: UUID?, snapshotAt: Instant?): UUID {
        val key = id ?: UUID.randomUUID()
        val now = clock.millis()
        val takenAt = snapshotAt?.toEpochMilli() ?: now
        queries.transaction {
            requireDraftOrAbsent(key, content.kind)
            requireNameFree(content.name, key)
            val updated = updateCalculated(key, content, priced, now, takenAt)
            if (updated == 0L) {
                insert(key, content, CalculationStatus.CALCULATED, now, priced, snapshotAt = takenAt)
            }
        }
        return key
    }

    /**
     * Replaces the input, the price snapshot, the result, the unit price, the name and the counterparty
     * of the calculated row [id] in place, for «Изменить без создания нового расчёта». The row keeps
     * its id, its date of creation, its [StoredCalculation.sourceId] and its drawings; the status
     * becomes «Рассчитан» again, whatever it was, and the date of the last change is now. [snapshotAt]
     * is when [snapshot] was taken; now, unless the prices are an older snapshot.
     *
     * The caller must have verified the administrator password: nothing is asked here. Throws
     * [NoSuchElementException] when there is no such row, [IllegalStateException] when it is a draft,
     * which is written with [saveDraft] and [saveCalculated], or of the other kind than [input], and
     * [DuplicateNameException] when another calculation has the name.
     */
    fun overwriteCalculated(id: UUID, input: CalculationInput, snapshot: PriceBook, result: CalcResult, snapshotAt: Instant? = null) =
        overwriteCalculated(id, Content.of(input), pricing(snapshot, result), snapshotAt)

    /** [overwriteCalculated] of a simplified calculation. */
    fun overwriteCalculated(id: UUID, input: QuickCalculationInput, snapshot: PriceBook, result: QuickResult, snapshotAt: Instant? = null) =
        overwriteCalculated(id, Content.of(input), pricing(snapshot, result), snapshotAt)

    private fun overwriteCalculated(id: UUID, content: Content, priced: Priced, snapshotAt: Instant?) {
        val now = clock.millis()
        queries.transaction {
            val row = queries.selectStatusAndKind(id.toString()).executeAsOneOrNull() ?: throw NoSuchElementException("No calculation $id")
            check(row.status != CalculationStatus.DRAFT.name) { "Draft $id has no calculation to overwrite, it is saved as a draft or calculated" }
            check(row.kind == content.kind.name) { "Calculation $id is a ${row.kind} one, it cannot be written with a ${content.kind} input" }
            requireNameFree(content.name, id)
            updateCalculated(id, content, priced, now, snapshotAt?.toEpochMilli() ?: now)
        }
    }

    private fun updateCalculated(id: UUID, content: Content, priced: Priced, now: Long, snapshotAt: Long): Long =
        queries.updateCalculated(
            name = content.name,
            nameSearch = content.nameSearch,
            counterparty = content.counterparty,
            counterpartySearch = content.counterpartySearch,
            status = CalculationStatus.CALCULATED.name,
            quantity = content.quantity.toLong(),
            unitPrice = priced.unitPrice,
            updatedAt = now,
            snapshotAt = snapshotAt,
            inputJson = content.inputJson,
            snapshotJson = priced.snapshotJson,
            resultJson = priced.resultJson,
            id = id.toString(),
        ).value

    /**
     * A new draft with the input and the price snapshot of the calculation [sourceId], pointing to
     * it, for «Изменить» (spec 8.3): «Рассчитать» on it uses the same prices. The source stays as it
     * was. The draft is named «<base> №N» ([nextRevisionName]), in its input as well as in its name column.
     */
    fun createRevisionDraft(sourceId: UUID): UUID {
        val source = existing(sourceId)
        check(source.status != CalculationStatus.DRAFT) { "Draft $sourceId is edited in place, it has no revisions" }
        val snapshot = checkNotNull(source.snapshot) { "Calculation $sourceId has no price snapshot" }
        val key = UUID.randomUUID()
        val now = clock.millis()
        queries.transaction {
            val content = source.content(nextRevisionName(source.name, ::isTaken))
            queries.insert(
                id = key.toString(),
                name = content.name,
                nameSearch = content.nameSearch,
                counterparty = content.counterparty,
                counterpartySearch = content.counterpartySearch,
                status = CalculationStatus.DRAFT.name,
                quantity = content.quantity.toLong(),
                unitPrice = null,
                createdAt = now,
                updatedAt = now,
                snapshotAt = source.snapshotAt?.toEpochMilli(),
                inputJson = content.inputJson,
                snapshotJson = encodeSnapshot(snapshot),
                resultJson = null,
                sourceId = sourceId.toString(),
                kind = content.kind.name,
            )
            attachments.copyAll(sourceId, key)
        }
        return key
    }

    /**
     * A new «Рассчитан» calculation made from [sourceId] with the given [input], [snapshot] and
     * [result]: a recalculation on current prices, or the same prices and result under a new run
     * size. The source stays as it was. [snapshotAt] is when [snapshot] was taken; now, unless
     * the prices are the source's snapshot. The revision is named «<base> №N» ([nextRevisionName]) after
     * the source, whatever name [input] has; the name is written into the stored input as well.
     */
    fun createRevision(sourceId: UUID, input: CalculationInput, snapshot: PriceBook, result: CalcResult, snapshotAt: Instant? = null): UUID =
        createRevision(sourceId, CalculationKind.FULL, pricing(snapshot, result), snapshotAt) { Content.of(input.copy(name = it)) }

    /** [createRevision] of a simplified calculation; [sourceId] must be one as well. */
    fun createRevision(sourceId: UUID, input: QuickCalculationInput, snapshot: PriceBook, result: QuickResult, snapshotAt: Instant? = null): UUID =
        createRevision(sourceId, CalculationKind.QUICK, pricing(snapshot, result), snapshotAt) { Content.of(input.copy(name = it)) }

    /** [contentNamed] is the content to write under the name given to it, the revision's own. */
    private fun createRevision(sourceId: UUID, kind: CalculationKind, priced: Priced, snapshotAt: Instant?, contentNamed: (String) -> Content): UUID {
        val source = existing(sourceId)
        check(source.kind == kind) { "Calculation $sourceId is a ${source.kind} one, its revision cannot be a $kind one" }
        val key = UUID.randomUUID()
        val now = clock.millis()
        queries.transaction {
            val content = contentNamed(nextRevisionName(source.name, ::isTaken))
            insert(key, content, CalculationStatus.CALCULATED, now, priced, snapshotAt = snapshotAt?.toEpochMilli() ?: now, sourceId = sourceId)
            attachments.copyAll(sourceId, key)
        }
        return key
    }

    /** The calculations made from [id], the newest first; every status, a draft in progress included. */
    fun revisionsOf(id: UUID): List<CalculationSummary> =
        queries.selectRevisions(id.toString()) { rowId, name, counterparty, status, quantity, unitPrice, createdAt, updatedAt, kind ->
            CalculationSummary(
                id = UUID.fromString(rowId),
                name = name,
                counterparty = counterparty,
                kind = CalculationKind.valueOf(kind),
                status = CalculationStatus.valueOf(status),
                quantity = runSize(CalculationKind.valueOf(kind), quantity),
                unitPrice = unitPrice?.let { Money(BigDecimal(it)) },
                createdAt = Instant.ofEpochMilli(createdAt),
                updatedAt = Instant.ofEpochMilli(updatedAt),
            )
        }.executeAsList().sortedWith(compareByDescending<CalculationSummary> { it.createdAt }.thenBy { it.id })

    /**
     * Deletes the draft [id] when it is a revision draft whose input and drawings still equal its
     * source's, apart from the name the program gave it: an automatic copy nobody changed, which
     * needs no password. True when it was deleted; any other calculation is left alone.
     */
    fun deleteUntouchedRevisionDraft(id: UUID): Boolean = queries.transactionWithResult {
        val draft = get(id)
        val source = draft?.takeIf { it.status == CalculationStatus.DRAFT }?.sourceId?.let { get(it) }
        if (draft == null || source == null || !draft.isUntouchedCopyOf(source) || !attachments.hasSameFiles(id, source.id)) {
            return@transactionWithResult false
        }
        remove(id)
        true
    }

    fun get(id: UUID): StoredCalculation? = queries.selectById(id.toString()).executeAsOneOrNull()?.toStored()

    /**
     * The list of spec 8.1, calculations of both kinds. [query] matches a part of the name or of the
     * counterparty in any letter case, Cyrillic included; an empty [statuses] means every status;
     * [from] and [to] bound the date of the last change, both days inclusive.
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

    /**
     * A new draft with the input of [id] and no snapshot, so it is priced on the current settings
     * (spec 8.3). It is named «<base> (N)» ([nextCopyName]), in its input as well as in its name column.
     */
    fun copy(id: UUID): UUID {
        val source = existing(id)
        val key = UUID.randomUUID()
        queries.transaction {
            insert(key, source.content(nextCopyName(source.name, ::isTaken)), CalculationStatus.DRAFT, clock.millis(), priced = null)
            attachments.copyAll(id, key)
        }
        return key
    }

    /**
     * Whether a calculation other than [exceptId] (any, when it is null) already has [name], by the
     * search form: in any letter case and without outer spaces. For the editor's live check. A blank
     * name is never taken.
     */
    fun isNameTaken(name: String, exceptId: UUID?): Boolean {
        val key = nameKey(name)
        return key.isNotEmpty() && queries.selectNameTaken(key, exceptId?.toString()).executeAsOne()
    }

    private fun isTaken(name: String) = isNameTaken(name, exceptId = null)

    private fun requireNameFree(name: String, id: UUID) {
        if (isNameTaken(name, id)) throw DuplicateNameException(name)
    }

    /**
     * Removes the calculation [id] and its attachments, whatever its status. Deleting needs the
     * administrator password ([AdminPassword]), which is asked by the caller; does nothing when
     * [id] is already gone.
     */
    fun delete(id: UUID) = remove(id)

    /** Sets one of the [CalculationStatus.MANUAL] statuses; a draft has no price and cannot get one. */
    fun setStatus(id: UUID, status: CalculationStatus) {
        require(status in CalculationStatus.MANUAL) { "Status $status is set by the program, not by hand" }
        queries.transaction {
            val current = existing(id)
            check(current.status != CalculationStatus.DRAFT) { "Draft $id has no price and cannot be marked $status" }
            queries.updateStatus(status.name, clock.millis(), id.toString())
        }
    }

    /**
     * Changes the run size of a draft. A calculated one gets a new run size as a revision
     * ([createRevision]). Throws [IllegalArgumentException] for a simplified calculation, which has no run size.
     */
    fun setQuantity(id: UUID, quantity: Int) {
        require(quantity > 0) { "Quantity must be positive, got $quantity" }
        queries.transaction {
            val current = existing(id)
            require(current is StoredCalculation.Full) { "Calculation $id is a ${current.kind} one, it has no run size" }
            check(current.status == CalculationStatus.DRAFT) { notChangedInPlace(id, current.status) }
            queries.updateQuantity(quantity.toLong(), encode(current.input.copy(quantity = quantity)), clock.millis(), id.toString())
        }
    }

    private fun existing(id: UUID) = get(id) ?: throw NoSuchElementException("No calculation $id")

    private fun remove(id: UUID) {
        queries.transaction {
            queries.delete(id.toString())
            attachments.removeAll(id)
        }
    }

    /**
     * A write over [id] is allowed when there is no such row yet (it is inserted) or the row is a
     * draft of the same [kind]: an input of the other kind would be read wrongly from then on.
     */
    private fun requireDraftOrAbsent(id: UUID, kind: CalculationKind) {
        val row = queries.selectStatusAndKind(id.toString()).executeAsOneOrNull() ?: return
        check(row.status == CalculationStatus.DRAFT.name) { notChangedInPlace(id, CalculationStatus.valueOf(row.status)) }
        check(row.kind == kind.name) { "Calculation $id is a ${row.kind} one, it cannot be written with a $kind input" }
    }

    private fun notChangedInPlace(id: UUID, status: CalculationStatus) =
        "Calculation $id is «${status.title}» and is never changed in place; make a revision of it"

    private fun pricing(snapshot: PriceBook, result: CalcResult) = Priced(
        unitPrice = result.unitPrice.amount.toPlainString(),
        snapshotJson = encodeSnapshot(snapshot),
        resultJson = storageJson.encodeToString(StoredResult.serializer(), StoredResult.of(result)),
    )

    private fun pricing(snapshot: PriceBook, result: QuickResult) = Priced(
        unitPrice = result.unitPrice.amount.toPlainString(),
        snapshotJson = encodeSnapshot(snapshot),
        resultJson = storageJson.encodeToString(StoredQuickResult.serializer(), StoredQuickResult.of(result)),
    )

    private fun insert(
        id: UUID,
        content: Content,
        status: CalculationStatus,
        now: Long,
        priced: Priced?,
        snapshotAt: Long? = priced?.let { now },
        sourceId: UUID? = null,
    ) {
        queries.insert(
            id = id.toString(),
            name = content.name,
            nameSearch = content.nameSearch,
            counterparty = content.counterparty,
            counterpartySearch = content.counterpartySearch,
            status = status.name,
            quantity = content.quantity.toLong(),
            unitPrice = priced?.unitPrice,
            createdAt = now,
            updatedAt = now,
            snapshotAt = snapshotAt,
            inputJson = content.inputJson,
            snapshotJson = priced?.snapshotJson,
            resultJson = priced?.resultJson,
            sourceId = sourceId?.toString(),
            kind = content.kind.name,
        )
    }

    private class Priced(val unitPrice: String, val snapshotJson: String, val resultJson: String)

    /**
     * An input of either kind as the row holds it: the columns the list reads and the input JSON.
     * Two contents are equal when they are of one kind and their JSON is the same text, which is
     * what a row would be written with.
     */
    private data class Content(
        val kind: CalculationKind,
        val name: String,
        val counterparty: String,
        val quantity: Int,
        val inputJson: String,
    ) {
        val nameSearch: String get() = nameKey(name)
        val counterpartySearch: String get() = searchForm(counterparty)

        companion object {
            fun of(input: CalculationInput) = Content(CalculationKind.FULL, input.name, input.counterparty, input.quantity, encode(input))

            /** A simplified calculation is written with a run size of 1, whatever [input] says. */
            fun of(input: QuickCalculationInput): Content {
                val written = input.copy(quantity = QUICK_RUN_SIZE)
                return Content(CalculationKind.QUICK, written.name, written.counterparty, written.quantity, encode(written))
            }
        }
    }

    /** The content of this calculation, under [name] when the program gives it a name of its own. */
    private fun StoredCalculation.content(name: String = this.name): Content = when (this) {
        is StoredCalculation.Full -> Content.of(input.copy(name = name))
        is StoredCalculation.Quick -> Content.of(input.copy(name = name))
    }

    /**
     * A revision draft is untouched when its content is its [source]'s, apart from the name: that is
     * the source's own, or the «<base> №N» the program gave the revision. Any other name was typed.
     */
    private fun StoredCalculation.isUntouchedCopyOf(source: StoredCalculation): Boolean {
        val programName = name == source.name || (hasRevisionNumber(name) && revisionBaseName(name) == revisionBaseName(source.name))
        return programName && content() == source.content(name)
    }

    private companion object {
        fun encode(input: CalculationInput) = storageJson.encodeToString(CalculationInput.serializer(), input)

        fun encode(input: QuickCalculationInput) = storageJson.encodeToString(QuickCalculationInput.serializer(), input)

        fun encodeSnapshot(snapshot: PriceBook) = storageJson.encodeToString(PriceBook.serializer(), snapshot)

        // Rows written by an older program are brought to the current model on every read (InputJsonUpgrade.kt).
        fun decodeInput(text: String): CalculationInput = storageJson.decodeFromJsonElement(
            CalculationInput.serializer(),
            upgradeInputJson(storageJson.parseToJsonElement(text)),
        )

        fun decodeResult(text: String): CalcResult = storageJson.decodeFromJsonElement(
            StoredResult.serializer(),
            upgradeResultJson(storageJson.parseToJsonElement(text)),
        ).toResult()

        fun Calculation.toStored(): StoredCalculation = when (CalculationKind.valueOf(kind)) {
            CalculationKind.FULL -> StoredCalculation.Full(
                id = UUID.fromString(id),
                status = CalculationStatus.valueOf(status),
                input = decodeInput(input_json),
                snapshot = snapshot_json?.let { storageJson.decodeFromString(PriceBook.serializer(), it) },
                snapshotAt = snapshot_at?.let(Instant::ofEpochMilli),
                result = result_json?.let(::decodeResult),
                createdAt = Instant.ofEpochMilli(created_at),
                updatedAt = Instant.ofEpochMilli(updated_at),
                sourceId = source_id?.let(UUID::fromString),
            )
            CalculationKind.QUICK -> StoredCalculation.Quick(
                id = UUID.fromString(id),
                status = CalculationStatus.valueOf(status),
                input = storageJson.decodeFromString(QuickCalculationInput.serializer(), input_json),
                snapshot = snapshot_json?.let { storageJson.decodeFromString(PriceBook.serializer(), it) },
                snapshotAt = snapshot_at?.let(Instant::ofEpochMilli),
                result = result_json?.let { storageJson.decodeFromString(StoredQuickResult.serializer(), it).toResult() },
                createdAt = Instant.ofEpochMilli(created_at),
                updatedAt = Instant.ofEpochMilli(updated_at),
                sourceId = source_id?.let(UUID::fromString),
            )
        }

        // A simplified calculation has no run size, whatever the quantity column of an old row holds.
        fun runSize(kind: CalculationKind, stored: Long): Int = if (kind == CalculationKind.QUICK) QUICK_RUN_SIZE else stored.toInt()

        fun SelectSummaries.toSummary() = CalculationSummary(
            id = UUID.fromString(id),
            name = name,
            counterparty = counterparty,
            kind = CalculationKind.valueOf(kind),
            status = CalculationStatus.valueOf(status),
            quantity = runSize(CalculationKind.valueOf(kind), quantity),
            unitPrice = unit_price?.let { Money(BigDecimal(it)) },
            createdAt = Instant.ofEpochMilli(created_at),
            updatedAt = Instant.ofEpochMilli(updated_at),
        )

        // Sorting is done here, not in SQL: names need Russian collation and prices are decimal strings.
        fun comparator(sort: CalculationSort): Comparator<CalculationSummary> {
            fun <T : Comparable<T>> by(selector: (CalculationSummary) -> T?): Comparator<CalculationSummary> {
                val values: Comparator<T> = if (sort.descending) reverseOrder() else naturalOrder()
                return compareBy(nullsLast(values), selector)
            }
            fun text(selector: (CalculationSummary) -> String): Comparator<CalculationSummary> =
                if (sort.descending) compareBy(nameCollator.reversed(), selector) else compareBy(nameCollator, selector)
            val primary = when (sort.column) {
                CalculationSortColumn.NAME -> text { it.name }
                CalculationSortColumn.COUNTERPARTY -> text { it.counterparty }
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
