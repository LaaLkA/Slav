package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.core.Applicability
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.applicability
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.SettingsRepository
import io.github.laalka.slav.data.StoredCalculation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The calculation editor (spec 7.1–7.3, 8.3). Every change is saved as a draft [autosaveDelay]
 * after the last one, and at once when the editor is left. «Рассчитать» prices the input on the
 * current settings for a new calculation or a copy, and on the calculation's snapshot when it was
 * calculated before; [onCalculated] receives the id of the saved calculation.
 */
class EditorModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    id: UUID?,
    private val onCalculated: (UUID) -> Unit,
    private val autosaveDelay: Duration = AUTOSAVE_DELAY,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var nextId = 0L

    /** The calculation as it was opened, or as the last «Рассчитать» saved it; null for a new one. */
    private var original: StoredCalculation? = id?.let { requireNotNull(calculations.get(it)) { "No calculation $it" } }
    private var id: UUID? = original?.id
    private val isNew = original == null

    /** Set when the calculation was priced before: it is recalculated on the same prices (spec 8.3). */
    private val snapshot: PriceBook? = original?.snapshot
    private var book: PriceBook = snapshot ?: settings.load()

    private var form: CalculationForm = original?.input?.toForm(::newId) ?: CalculationForm(parts = listOf(newPart(1)))

    /** What the database holds for this calculation; null while nothing is stored. */
    private var stored: CalculationInput? = original?.input

    /** The form was changed since the last draft save; opening and leaving without a change writes nothing. */
    private var unsaved = false

    /** A draft was written over a priced calculation, which has lost its result until restored or recalculated. */
    private var overwritten = false
    private var pendingSave: Job? = null
    private var calcErrors: List<ReadableError> = emptyList()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** The name of the calculation as typed, for the question asked on leaving. */
    val name: String get() = form.name.trim()

    fun onNameChange(text: String) = edit { it.copy(name = text) }

    fun onQuantityChange(text: String) = edit { it.copy(quantity = text) }

    fun addPart() = edit { it.copy(parts = it.parts + newPart(it.parts.size + 1)) }

    /** The copy is placed right after its original (spec 7.1). */
    fun copyPart(part: Long) = edit { form ->
        val index = form.parts.indexOfFirst { it.id == part }
        if (index < 0) return@edit form
        val copy = form.parts[index].copyWithNewIds()
        form.copy(parts = form.parts.toMutableList().apply { add(index + 1, copy) })
    }

    fun deletePart(part: Long) = edit { form -> form.copy(parts = form.parts.filterNot { it.id == part }) }

    fun onPartNameChange(part: Long, text: String) = editPart(part) { it.copy(name = text) }

    fun onPartQuantityChange(part: Long, text: String) = editPart(part) { it.copy(quantity = text) }

    /** A new kind has other sizes, so the size is picked again; the width belongs to a sheet only. */
    fun onKindChange(part: Long, kind: SortamentKind) = editMaterial(part) { material ->
        if (material.kind == kind) material
        else material.copy(kind = kind, size = null, width = if (kind == SortamentKind.SHEET) material.width else "")
    }

    fun onGradeChange(part: Long, grade: String) = editMaterial(part) { it.copy(grade = grade) }

    fun onSizeChange(part: Long, size: SizeChoice) = editMaterial(part) { it.copy(size = size) }

    fun onLengthChange(part: Long, text: String) = editMaterial(part) { it.copy(length = text) }

    fun onWidthChange(part: Long, text: String) = editMaterial(part) { it.copy(width = text) }

    /** Checking an operation opens its parameters with their defaults; only an available operation can be checked. */
    fun setChecked(line: LineRef, checked: Boolean) = when (line) {
        is LineRef.MaterialOf -> Unit
        is LineRef.OfPart -> editPart(line.part) { part ->
            val available = applicability(line.kind, part.material.read().material, book) == Applicability.APPLICABLE
            when {
                !checked -> part.copy(operations = part.operations - line.kind)
                !available || line.kind in part.operations -> part
                else -> part.copy(operations = part.operations + (line.kind to newPartOperation(line.kind)))
            }
        }
        is LineRef.OfProduct -> edit { form ->
            when {
                !checked -> form.copy(productOperations = form.productOperations - line.kind)
                line.kind in form.productOperations -> form
                else -> form.copy(productOperations = form.productOperations + (line.kind to newProductOperation(line.kind)))
            }
        }
    }

    fun onExtraAmountChange(line: LineRef, text: String) = editExtra(line) { it.copy(amount = text) }

    fun onExtraCommentChange(line: LineRef, text: String) = editExtra(line) { it.copy(comment = text) }

    fun onCutModeChange(part: Long, mode: CutMode) = editLaserSheet(part) { it.copy(mode = mode) }

    fun onContourLengthChange(part: Long, text: String) = editLaserSheet(part) { it.copy(contourLength = text) }

    fun onPiercesChange(part: Long, text: String) = editLaserSheet(part) { it.copy(pierces = text) }

    fun onCutsChange(part: Long, text: String) =
        editOperation(LineRef.OfPart(part, OperationKind.TUBE_LASER)) { (it as TubeLaserForm).copy(cuts = text) }

    fun addRow(line: LineRef) = editOperation(line) { op ->
        if (op is HolesForm) op.copy(rows = op.rows + HoleRowForm(newId())) else op.withCountRows { it + CountRowForm(newId()) }
    }

    fun deleteRow(line: LineRef, row: Long) = editOperation(line) { op ->
        if (op is HolesForm) op.copy(rows = op.rows.filterNot { it.id == row }) else op.withCountRows { rows -> rows.filterNot { it.id == row } }
    }

    /** The diameter of a hole, the length of a bend or a seam. */
    fun onRowLengthChange(line: LineRef, row: Long, text: String) = editOperation(line) { op ->
        if (op is HolesForm) op.copy(rows = op.rows.map { if (it.id == row) it.copy(diameter = text) else it })
        else op.withCountRows { rows -> rows.map { if (it.id == row) it.copy(length = text) else it } }
    }

    fun onRowCountChange(line: LineRef, row: Long, text: String) = editOperation(line) { op ->
        if (op is HolesForm) op.copy(rows = op.rows.map { if (it.id == row) it.copy(count = text) else it })
        else op.withCountRows { rows -> rows.map { if (it.id == row) it.copy(count = text) else it } }
    }

    fun onThroughBothWallsChange(part: Long, row: Long, checked: Boolean) =
        editOperation(LineRef.OfPart(part, OperationKind.HOLES)) { op ->
            (op as HolesForm).copy(rows = op.rows.map { if (it.id == row) it.copy(throughBothWalls = checked) else it })
        }

    fun onWeldTypeChange(line: LineRef, weldType: String) = editOperation(line) { op ->
        when (op) {
            is WeldingForm -> op.copy(weldType = weldType)
            is AssemblyWeldingForm -> op.copy(weldType = weldType)
            else -> op
        }
    }

    fun onAssemblyGroupChange(group: String) = editAssemblyWelding { it.copy(group = group) }

    fun onAssemblyThicknessChange(thicknessMm: BigDecimal) = editAssemblyWelding { it.copy(thicknessMm = thicknessMm) }

    fun onAreaOverrideChange(line: LineRef, text: String) = editOperation(line) { (it as PaintingForm).copy(areaOverride = text) }

    /**
     * Spec 7.1, 7.3, 8.3. Ok: the calculation is saved as «Рассчитан» with the prices it used, and
     * [onCalculated] is called. Failed: every error is listed and the input stays a draft.
     */
    fun calculate() {
        val read = form.read()
        if (read.errors.isNotEmpty()) return
        // The settings are read at the press: a price filled in a moment ago counts.
        val prices = snapshot ?: settings.load()
        when (val outcome = Calculator(prices).calculate(read.input)) {
            is CalcOutcome.Failed -> {
                flush()
                calcErrors = outcome.errors.map { it.readable(onSnapshot = snapshot != null) }
                refresh()
            }
            is CalcOutcome.Ok -> {
                pendingSave?.cancel()
                pendingSave = null
                val saved = calculations.saveCalculated(read.input, prices, outcome.result, id)
                id = saved
                original = calculations.get(saved)
                stored = read.input
                unsaved = false
                overwritten = false
                calcErrors = emptyList()
                refresh()
                onCalculated(saved)
            }
        }
    }

    /** Writes the pending draft now. */
    fun flush() {
        pendingSave?.cancel()
        pendingSave = null
        saveDraft()
    }

    /**
     * Saves what is pending before the editor is left. True when the user must choose between
     * keeping the edit as a draft and [discardChanges] (spec 8.3): a priced calculation was
     * changed and not recalculated. An edit undone by hand restores the calculation silently.
     */
    fun leave(): Boolean {
        flush()
        val priced = original?.takeIf { it.result != null } ?: return false
        if (!overwritten) return false
        if (stored == priced.input) {
            discardChanges()
            return false
        }
        return true
    }

    /** «Отменить изменения»: the calculation gets back its input, result and snapshot as they were. */
    fun discardChanges() {
        val priced = original?.takeIf { it.result != null } ?: return
        pendingSave?.cancel()
        pendingSave = null
        if (overwritten) calculations.restore(priced)
        unsaved = false
        overwritten = false
        stored = priced.input
    }

    /** Back from the settings: a calculation on current prices sees what was changed there. */
    fun resume() {
        if (snapshot != null) return
        book = settings.load()
        calcErrors = emptyList()
        edit { it }
        refresh()
    }

    private fun edit(transform: (CalculationForm) -> CalculationForm) {
        val next = transform(form).withAvailableOperations()
        if (next == form) return
        form = next
        unsaved = true
        calcErrors = emptyList()
        refresh()
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(autosaveDelay)
            pendingSave = null
            saveDraft()
        }
    }

    private fun saveDraft() {
        if (!unsaved) return
        unsaved = false
        val draft = form.read().input
        if (draft == stored) return
        id = calculations.saveDraft(draft, id)
        stored = draft
        if (original?.result != null) overwritten = true
    }

    private fun refresh() {
        _state.value = build()
    }

    private fun build(): EditorState {
        val snapshotAt = original?.snapshotAt.takeIf { snapshot != null }
        val priceSource = if (snapshotAt != null) {
            "Цены — снимок от ${DATE.format(snapshotAt.atZone(zone))}"
        } else {
            "Цены — текущие настройки"
        }
        val hint = if (snapshotAt != null) {
            "Расчёт ведётся по снимку цен от ${DATE.format(snapshotAt.atZone(zone))}: цены из настроек в него не попадают. " +
                "Чтобы считать по текущим ценам, сделайте копию расчёта. Изменения сохранены как черновик."
        } else {
            "Заполните эти цены в «Настройках» и нажмите «Рассчитать» ещё раз. Расчёт сохранён как черновик."
        }
        return editorState(form, form.read(), book, isNew, priceSource, calcErrors, hint)
    }

    private fun editPart(part: Long, transform: (PartForm) -> PartForm) =
        edit { form -> form.copy(parts = form.parts.map { if (it.id == part) transform(it) else it }) }

    private fun editMaterial(part: Long, transform: (MaterialForm) -> MaterialForm) =
        editPart(part) { it.copy(material = transform(it.material)) }

    private fun editLaserSheet(part: Long, transform: (LaserSheetForm) -> LaserSheetForm) =
        editOperation(LineRef.OfPart(part, OperationKind.LASER_SHEET)) { transform(it as LaserSheetForm) }

    private fun editAssemblyWelding(transform: (AssemblyWeldingForm) -> AssemblyWeldingForm) =
        editOperation(LineRef.OfProduct(OperationKind.WELDING)) { transform(it as AssemblyWeldingForm) }

    /** [transform] gets the checked operation of [line] and returns it changed; an unchecked one is left alone. */
    private fun editOperation(line: LineRef, transform: (OperationForm) -> OperationForm) = when (line) {
        is LineRef.MaterialOf -> Unit
        is LineRef.OfPart -> editPart(line.part) { part ->
            val op = part.operations[line.kind] ?: return@editPart part
            part.copy(operations = part.operations + (line.kind to transform(op) as PartOperationForm))
        }
        is LineRef.OfProduct -> edit { form ->
            val op = form.productOperations[line.kind] ?: return@edit form
            form.copy(productOperations = form.productOperations + (line.kind to transform(op) as ProductOperationForm))
        }
    }

    private fun editExtra(line: LineRef, transform: (ExtraForm) -> ExtraForm) = when (line) {
        is LineRef.MaterialOf -> editPart(line.part) { it.copy(materialExtra = transform(it.materialExtra)) }
        is LineRef.OfPart, is LineRef.OfProduct -> editOperation(line) { it.withExtra(transform(it.extra)) }
    }

    /**
     * Operations the material no longer allows are dropped (spec 6.1), and «через обе стенки» is
     * cleared off a material that is not a tube: the form holds only what the screen shows.
     */
    private fun CalculationForm.withAvailableOperations() = copy(
        parts = parts.map { part ->
            val material = part.material.read().material
            val operations = part.operations
                .filterKeys { applicability(it, material, book) == Applicability.APPLICABLE }
                .mapValues { (_, op) ->
                    if (op is HolesForm && !part.material.kind.hasTwoWalls) op.copy(rows = op.rows.map { it.copy(throughBothWalls = false) }) else op
                }
            if (operations == part.operations) part else part.copy(operations = operations)
        },
    )

    private fun newId() = nextId++

    private fun newPart(number: Int) = PartForm(newId(), name = "Деталь $number")

    private fun newPartOperation(kind: OperationKind): PartOperationForm = when (kind) {
        OperationKind.LASER_SHEET -> LaserSheetForm()
        OperationKind.TUBE_LASER -> TubeLaserForm()
        OperationKind.HOLES -> HolesForm(listOf(HoleRowForm(newId())))
        OperationKind.BENDING -> BendingForm(listOf(CountRowForm(newId())))
        OperationKind.WELDING -> WeldingForm(book.general.weldTypes.firstOrNull(), listOf(CountRowForm(newId())))
        OperationKind.PAINTING -> PaintingForm()
    }

    private fun newProductOperation(kind: OperationKind): ProductOperationForm = when (kind) {
        OperationKind.WELDING -> AssemblyWeldingForm(book.general.weldTypes.firstOrNull(), group = null, thicknessMm = null, rows = listOf(CountRowForm(newId())))
        OperationKind.PAINTING -> PaintingForm()
        OperationKind.LASER_SHEET, OperationKind.TUBE_LASER, OperationKind.HOLES, OperationKind.BENDING ->
            error("$kind is not a product operation")
    }

    private fun PartForm.copyWithNewIds() = copy(
        id = newId(),
        name = "$name (копия)",
        operations = operations.mapValues { (_, op) ->
            when (op) {
                is HolesForm -> op.copy(rows = op.rows.map { it.copy(id = newId()) })
                else -> op.withCountRows { rows -> rows.map { it.copy(id = newId()) } } as PartOperationForm
            }
        },
    )

    companion object {
        /** Spec 7.2: a draft is saved this long after the last change. */
        val AUTOSAVE_DELAY: Duration = 500.milliseconds

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
