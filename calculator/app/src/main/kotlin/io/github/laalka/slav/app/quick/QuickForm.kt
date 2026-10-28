package io.github.laalka.slav.app.quick

import io.github.laalka.slav.app.editor.ExtraForm
import io.github.laalka.slav.app.editor.FormReader
import io.github.laalka.slav.app.editor.countText
import io.github.laalka.slav.app.editor.positiveText
import io.github.laalka.slav.app.editor.toForm
import io.github.laalka.slav.core.QuickCalculationInput
import java.math.BigDecimal

// What the simplified editor holds: the fields as typed and the choices as made. A draft is stored
// as a QuickCalculationInput, which has no empty fields, so the rules of the full editor apply: an
// unfilled number is stored as 0, an unchosen grade as "" and an unchosen thickness as 0.

/** Test tags of the simplified editor. A field's tag is also the key of its error. */
object QuickTags {
    const val NAME = "quick/name"
    const val QUANTITY = "quick/quantity"
    const val GRADE = "quick/grade"
    const val THICKNESS = "quick/thickness"
    const val SHEET_AREA = "quick/sheet-area"
    const val CUT_TIME = "quick/cut-time"
    const val MASS = "quick/mass"

    /** The line tag of [line]; its «Доп. операции» fields end in "/extra/amount" and "/extra/comment". */
    fun line(line: QuickLine) = "quick/${line.name.lowercase()}"
}

/** The two lines of a simplified calculation, each with its own «Доп. операции». */
enum class QuickLine { MATERIAL, CUT }

/** [thicknessMm] is picked from the sheet thicknesses of the sortament, null until picked. */
data class QuickForm(
    val name: String = "",
    val quantity: String = "1",
    val grade: String? = null,
    val thicknessMm: BigDecimal? = null,
    val sheetArea: String = "",
    val cutTime: String = "",
    val materialExtra: ExtraForm = ExtraForm(),
    val cutExtra: ExtraForm = ExtraForm(),
) {
    fun extra(line: QuickLine): ExtraForm = when (line) {
        QuickLine.MATERIAL -> materialExtra
        QuickLine.CUT -> cutExtra
    }

    fun withExtra(line: QuickLine, extra: ExtraForm): QuickForm = when (line) {
        QuickLine.MATERIAL -> copy(materialExtra = extra)
        QuickLine.CUT -> copy(cutExtra = extra)
    }
}

/**
 * [input] is always complete enough to be stored as a draft; it can be calculated only when
 * [errors] is empty. [errors] maps the tag of every field that stops the calculation to its message.
 */
class QuickRead(val input: QuickCalculationInput, val errors: Map<String, String>)

/** Reads the form in one pass, so the fields highlighted are exactly the ones that keep «Рассчитать» disabled (spec 7.1). */
fun QuickForm.read(): QuickRead {
    val reader = FormReader()
    val input = QuickCalculationInput(
        name = reader.text(QuickTags.NAME, name),
        quantity = reader.count(QuickTags.QUANTITY, quantity),
        grade = reader.choice(QuickTags.GRADE, grade).orEmpty(),
        thicknessMm = reader.choice(QuickTags.THICKNESS, thicknessMm) ?: BigDecimal.ZERO,
        sheetAreaM2 = reader.positive(QuickTags.SHEET_AREA, sheetArea),
        cutTimeMin = reader.positive(QuickTags.CUT_TIME, cutTime),
        materialExtra = reader.extra(QuickTags.line(QuickLine.MATERIAL), materialExtra),
        cutExtra = reader.extra(QuickTags.line(QuickLine.CUT), cutExtra),
    )
    return QuickRead(input, reader.errors)
}

/** The form of a stored input, a draft included. */
fun QuickCalculationInput.toForm(): QuickForm = QuickForm(
    name = name,
    quantity = countText(quantity),
    grade = grade.ifEmpty { null },
    thicknessMm = thicknessMm.takeIf { it.signum() > 0 },
    sheetArea = positiveText(sheetAreaM2),
    cutTime = positiveText(cutTimeMin),
    materialExtra = materialExtra.toForm(),
    cutExtra = cutExtra.toForm(),
)
