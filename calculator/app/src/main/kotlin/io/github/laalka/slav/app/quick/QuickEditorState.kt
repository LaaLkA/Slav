package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.editor.ChoiceView
import io.github.laalka.slav.app.editor.ExtraView
import io.github.laalka.slav.app.editor.FieldView
import io.github.laalka.slav.app.editor.ReadableError
import io.github.laalka.slav.app.settings.sheetThicknesses
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.app.ui.formatMeasure
import io.github.laalka.slav.core.PriceBook
import java.math.BigDecimal

data class QuickEditorState(
    /** Opened by «Новый расчёт» rather than on a saved calculation. */
    val isNew: Boolean,
    /** Which prices «Рассчитать» uses: the current settings or the calculation's snapshot (spec 8.3). */
    val priceSource: String,
    val currency: String,
    val name: FieldView,
    val quantity: FieldView,
    val grade: ChoiceView,
    val thickness: ChoiceView,
    /** The thicknesses behind [thickness]'s options, in the same order. */
    val thicknessOptions: List<BigDecimal>,
    val sheetArea: FieldView,
    val cutTime: FieldView,
    /** The mass of the sheet; null until the grade, the thickness and the area are filled. */
    val mass: String?,
    val materialExtra: ExtraView,
    val cutExtra: ExtraView,
    /** How many fields keep «Рассчитать» disabled; each of them is highlighted. */
    val missingCount: Int,
    /** Why the last «Рассчитать» stopped (spec 7.3); empty when it did not. */
    val calcErrors: List<ReadableError>,
    val calcErrorsHint: String?,
) {
    val canCalculate: Boolean get() = missingCount == 0
}

/** What the screen draws for [form]: [read]'s errors highlight the fields, [book] supplies the choices. */
internal fun quickEditorState(
    form: QuickForm,
    read: QuickRead,
    book: PriceBook,
    isNew: Boolean,
    priceSource: String,
    calcErrors: List<ReadableError>,
    calcErrorsHint: String?,
): QuickEditorState {
    val errors = read.errors
    val thicknesses = book.sortament.sheetThicknesses()
    fun field(tag: String, text: String) = FieldView(tag, text, errors[tag])
    fun extra(line: QuickLine): ExtraView {
        val tag = QuickTags.line(line)
        val extra = form.extra(line)
        return ExtraView(field("$tag/extra/amount", extra.amount), field("$tag/extra/comment", extra.comment))
    }
    return QuickEditorState(
        isNew = isNew,
        priceSource = priceSource,
        currency = book.general.currencySymbol,
        name = field(QuickTags.NAME, form.name),
        quantity = field(QuickTags.QUANTITY, form.quantity),
        grade = ChoiceView(
            QuickTags.GRADE, "Марка", form.grade, book.grades.map { it.name },
            errors[QuickTags.GRADE]?.let { if (book.grades.isEmpty()) NO_GRADES else it },
        ),
        thickness = ChoiceView(
            QuickTags.THICKNESS, "Толщина t, мм", form.thicknessMm?.toInputText(), thicknesses.map { it.toInputText() },
            errors[QuickTags.THICKNESS]?.let { if (thicknesses.isEmpty()) NO_SIZES else it },
        ),
        thicknessOptions = thicknesses,
        sheetArea = field(QuickTags.SHEET_AREA, form.sheetArea),
        cutTime = field(QuickTags.CUT_TIME, form.cutTime),
        mass = mass(read, book),
        materialExtra = extra(QuickLine.MATERIAL),
        cutExtra = extra(QuickLine.CUT),
        missingCount = errors.size,
        calcErrors = calcErrors,
        calcErrorsHint = calcErrorsHint.takeIf { calcErrors.isNotEmpty() },
    )
}

/** The mass of the sheet by the typed area, once a known grade, a thickness and the area are filled. */
private fun mass(read: QuickRead, book: PriceBook): String? {
    if (QuickTags.GRADE in read.errors || QuickTags.THICKNESS in read.errors || QuickTags.SHEET_AREA in read.errors) return null
    val density = book.grades.firstOrNull { it.name == read.input.grade }?.densityKgM3 ?: return null
    return "${formatMeasure(read.input.massKg(density))} кг"
}

private const val NO_GRADES = "Нет марок — добавьте их в «Настройки → Материалы»"
private const val NO_SIZES = "Нет размеров — добавьте их в «Настройки → Сортамент»"
