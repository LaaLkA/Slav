package io.github.laalka.slav.app.library

import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.toInputText
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.SheetShape
import io.github.laalka.slav.data.PartTemplate
import java.math.BigDecimal
import java.util.UUID

/** A template as the library lists it; [summary] says what the part is made of, e.g. «Лист Ст3 2 мм, 1000×1000». */
data class LibraryRow(val id: UUID, val name: String, val summary: String)

internal fun PartTemplate.toRow() = LibraryRow(id, name, part.material.summary())

/**
 * The material of a part in one line: the kind, the grade and the size, then the dimensions. A
 * part saved unfinished has blanks (no grade, zero sizes); they are left out, not shown as zeros.
 */
internal fun Material.summary(): String {
    val head = listOf(kindTitle(), grade, sizeText()).filter { it.isNotEmpty() }.joinToString(" ")
    val tail = dimensionsText()
    return if (tail.isEmpty()) head else "$head, $tail"
}

private fun Material.kindTitle(): String = when (this) {
    is Material.Sheet -> SortamentKind.SHEET.title
    is Material.RoundTube -> SortamentKind.ROUND_TUBE.title
    is Material.ProfileTube -> SortamentKind.PROFILE_TUBE.title
    is Material.Angle -> SortamentKind.ANGLE.title
    is Material.Channel -> SortamentKind.CHANNEL.title
    is Material.IBeam -> SortamentKind.I_BEAM.title
}

/** The section of a long product or the thickness of a sheet. */
private fun Material.sizeText(): String = when (this) {
    is Material.Sheet -> number(thicknessMm).orEmptyWith(suffix = " мм")
    is Material.RoundTube -> joinSides(diameterMm, wallMm).orEmptyWith(suffix = " мм")
    is Material.ProfileTube -> joinSides(sideAMm, sideBMm, wallMm).orEmptyWith(suffix = " мм")
    is Material.Angle -> size.designation
    is Material.Channel -> size.designation
    is Material.IBeam -> size.designation
}

private fun Material.dimensionsText(): String = when (this) {
    is Material.Sheet -> when (val outline = shape) {
        is SheetShape.Rectangle -> joinSides(outline.lengthMm, outline.widthMm)
        is SheetShape.Circle -> number(outline.radiusMm).orEmptyWith(prefix = "круг R")
        is SheetShape.Ring -> {
            val outer = number(outline.outerRadiusMm)
            val inner = number(outline.innerRadiusMm)
            if (outer.isEmpty() || inner.isEmpty()) "" else "кольцо R$outer / r$inner"
        }
        is SheetShape.Free -> number(outline.areaMm2).orEmptyWith(prefix = "произвольная форма, ", suffix = " мм²")
    }
    is Material.LongProduct -> number(lengthMm).orEmptyWith(prefix = "длина ", suffix = " мм")
}

/** «1000×500»; empty while any of the [sides] is not filled in. */
private fun joinSides(vararg sides: BigDecimal): String {
    val texts = sides.map(::number)
    return if (texts.any { it.isEmpty() }) "" else texts.joinToString("×")
}

private fun number(value: BigDecimal): String = if (value.signum() > 0) value.toInputText() else ""

/** This text between [prefix] and [suffix]; nothing at all when it is empty, so a blank leaves no stray words. */
private fun String.orEmptyWith(suffix: String = "", prefix: String = ""): String = if (isEmpty()) "" else prefix + this + suffix
