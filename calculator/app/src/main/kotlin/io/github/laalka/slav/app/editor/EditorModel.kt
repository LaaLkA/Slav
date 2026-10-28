package io.github.laalka.slav.app.editor

import io.github.laalka.slav.app.FileOpener
import io.github.laalka.slav.app.attachments.AttachmentsModel
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.core.Applicability
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.applicability
import io.github.laalka.slav.data.AttachmentRepository
import io.github.laalka.slav.data.CalculationRepository
import io.github.laalka.slav.data.CalculationStatus
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
 * The calculation editor (spec 7.1–7.3, 8.3). It edits a draft: a new one, one left unfinished, a
 * copy, or the revision draft that «Изменить» makes of a calculated record, which is never edited
 * itself. Every change is saved as a draft [autosaveDelay] after the last one, and at once when the
 * editor is left. «Рассчитать» prices the input on the current settings for a new calculation or a
 * copy, and on the snapshot of the revision draft's source; [onCalculated] receives the id of the
 * saved calculation.
 */
class EditorModel(
    private val calculations: CalculationRepository,
    private val settings: SettingsRepository,
    attachmentRepository: AttachmentRepository,
    fileOpener: FileOpener,
    private val scope: CoroutineScope,
    id: UUID?,
    private val onCalculated: (UUID) -> Unit,
    private val autosaveDelay: Duration = AUTOSAVE_DELAY,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var nextId = 0L

    /** The draft as it was opened, or the calculation the last «Рассчитать» saved it as; null for a new one. */
    private var original: StoredCalculation? = id?.let { requireNotNull(calculations.get(it)) { "No calculation $it" } }
    private var id: UUID? = original?.id
    private val isNew = original == null

    init {
        original?.let { require(it.status == CalculationStatus.DRAFT) { "Only a draft is edited, ${it.id} is «${it.status.title}»" } }
    }

    /** Set for a revision draft: it is calculated on the prices of its source (spec 8.3). */
    private val snapshot: PriceBook? = original?.snapshot
    private var book: PriceBook = snapshot ?: settings.load()

    private var form: CalculationForm = original?.input?.toForm(::newId) ?: CalculationForm(parts = listOf(newPart(1)))

    /** What the database holds for this calculation; null while nothing is stored. */
    private var stored: CalculationInput? = original?.input

    /** The form was changed since the last draft save; opening and leaving without a change writes nothing. */
    private var unsaved = false
    private var pendingSave: Job? = null
    private var calcErrors: List<ReadableError> = emptyList()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** The drawings of the calculation; the editor only edits drafts, so removing one needs no password. */
    val attachments = AttachmentsModel(
        attachmentRepository,
        fileOpener,
        // this.id is the property, which follows the saves; a bare id here would be the constructor parameter.
        calculationId = { this.id },
        storedCalculationId = ::storedId,
        passwordGuard = null,
    )

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

    /** The mass from the drawing; blank means the mass computed from the dimensions. */
    fun onMassOverrideChange(part: Long, text: String) = editPart(part) { it.copy(massOverride = text) }

    /** A new kind has other sizes, so the size is picked again; the shape belongs to a sheet only. */
    fun onKindChange(part: Long, kind: SortamentKind) = editMaterial(part) { material ->
        when {
            material.kind == kind -> material
            kind == SortamentKind.SHEET -> material.copy(kind = kind, size = null)
            else -> material.copy(kind = kind, size = null).withoutSheetShape()
        }
    }

    fun onShapeChange(part: Long, shape: ShapeKind) = editMaterial(part) { it.copy(shape = shape) }

    fun onGradeChange(part: Long, grade: String) = editMaterial(part) { it.copy(grade = grade) }

    fun onSizeChange(part: Long, size: SizeChoice) = editMaterial(part) { it.copy(size = size) }

    /** A dimension of the material: the length of a long product, or a dimension of the sheet's shape. */
    fun onDimensionChange(part: Long, field: DimensionField, text: String) = editMaterial(part) { it.withDimension(field, text) }

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

    /** The diameter of a hole or the length of a bend. */
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

    fun onAssemblyWeldTypeChange(weldType: String) = editAssemblyWelding { it.copy(weldType = weldType) }

    /** The total length of all seams of the assembly. */
    fun onSeamLengthChange(text: String) = editAssemblyWelding { it.copy(seamLength = text) }

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
                // A revision draft keeps the date of the snapshot it was made with: the prices are the same.
                val saved = calculations.saveCalculated(read.input, prices, outcome.result, id, snapshotAt = original?.snapshotAt.takeIf { snapshot != null })
                id = saved
                original = calculations.get(saved)
                stored = read.input
                unsaved = false
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
     * Saves what is pending before the editor is left. A revision draft nothing was changed in
     * is an automatic copy of its source and is deleted silently, without a password.
     */
    fun leave() {
        flush()
        if (original?.sourceId != null) id?.let { calculations.deleteUntouchedRevisionDraft(it) }
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

    /** The id drawings are attached under: a calculation never saved yet is saved as a draft first. */
    private fun storedId(): UUID {
        flush()
        id?.let { return it }
        val draft = form.read().input
        return calculations.saveDraft(draft).also {
            id = it
            stored = draft
        }
    }

    private fun saveDraft() {
        if (!unsaved) return
        unsaved = false
        val draft = form.read().input
        if (draft == stored) return
        id = calculations.saveDraft(draft, id)
        stored = draft
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
        OperationKind.WELDING, OperationKind.PAINTING -> error("$kind is not a part operation")
    }

    private fun newProductOperation(kind: OperationKind): ProductOperationForm = when (kind) {
        OperationKind.WELDING -> AssemblyWeldingForm(book.general.weldTypes.firstOrNull(), group = null, thicknessMm = null)
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
