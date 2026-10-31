package io.github.laalka.slav.app.developer

import io.github.laalka.slav.app.editor.CalculationForm
import io.github.laalka.slav.app.editor.HolesForm
import io.github.laalka.slav.app.editor.BendingForm
import io.github.laalka.slav.app.editor.ShapeKind
import io.github.laalka.slav.app.editor.read
import io.github.laalka.slav.app.quick.QuickForm
import io.github.laalka.slav.app.quick.read
import io.github.laalka.slav.app.settings.SortamentKind
import io.github.laalka.slav.app.settings.parseDecimal
import io.github.laalka.slav.core.Applicability
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.QuickCalculator
import io.github.laalka.slav.core.QuickOutcome
import io.github.laalka.slav.core.applicability
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RandomCalculationTest {
    private val book = PriceBook.default()

    private fun form(seed: Int, book: PriceBook = this.book): CalculationForm {
        var next = 0L
        return RandomCalculation(book, Random(seed)).calculationForm { next++ }
    }

    private fun quick(seed: Int, book: PriceBook = this.book): QuickForm = RandomCalculation(book, Random(seed)).quickForm()

    /** The ids of the parts and the rows of [form]: the keys the editor tells them apart by. */
    private fun ids(form: CalculationForm): List<Long> = form.parts.flatMap { part ->
        listOf(part.id) + part.operations.values.flatMap { op ->
            when (op) {
                is HolesForm -> op.rows.map { it.id }
                is BendingForm -> op.rows.map { it.id }
                else -> emptyList()
            }
        }
    }

    private fun CalculationForm.operationKinds(): Set<OperationKind> =
        parts.flatMap { it.operations.keys }.toSet() + productOperations.keys

    @Test
    fun `200 seeds give forms that read without field errors and calculate Ok, every shape, kind and operation among them`() {
        val names = mutableSetOf<String>()
        val shapes = mutableSetOf<ShapeKind>()
        val kinds = mutableSetOf<SortamentKind>()
        val operations = mutableSetOf<OperationKind>()
        repeat(200) { seed ->
            val form = form(seed)
            val read = form.read()
            assertEquals(emptyMap(), read.errors, "seed $seed reads with errors")
            val outcome = Calculator(book).calculate(read.input)
            assertIs<CalcOutcome.Ok>(outcome, "seed $seed: $outcome")

            assertTrue(form.name.matches(Regex("Тест [A-Z0-9]{6}")), "seed $seed name '${form.name}'")
            assertTrue(names.add(form.name), "seed $seed repeats the name '${form.name}'")
            assertTrue(form.parts.size in 1..4, "seed $seed has ${form.parts.size} parts")
            assertTrue(form.counterparty.startsWith("ООО «"), "seed $seed counterparty '${form.counterparty}'")
            form.parts.forEach { part ->
                kinds += part.material.kind
                if (part.material.kind == SortamentKind.SHEET) shapes += part.material.shape
                // Each chosen operation is one core allows for the material, not only one that happened to be priced.
                part.operations.keys.forEach { kind ->
                    assertEquals(
                        Applicability.APPLICABLE,
                        applicability(kind, part.material.read().material, book),
                        "seed $seed: $kind on a ${part.material.kind}",
                    )
                }
            }
            operations += form.operationKinds()
        }
        assertEquals(ShapeKind.entries.toSet(), shapes, "shapes seen")
        assertEquals(SortamentKind.entries.toSet(), kinds, "kinds seen")
        assertEquals(OperationKind.entries.toSet(), operations, "operations seen")
    }

    @Test
    fun `200 seeds give quick forms that read without field errors and calculate Ok with unique names`() {
        val names = mutableSetOf<String>()
        repeat(200) { seed ->
            val form = quick(seed)
            val read = form.read()
            assertEquals(emptyMap(), read.errors, "seed $seed reads with errors")
            val outcome = QuickCalculator(book).calculate(read.input)
            assertIs<QuickOutcome.Ok>(outcome, "seed $seed: $outcome")

            assertTrue(names.add(form.name), "seed $seed repeats the name '${form.name}'")
            assertTrue(read.input.sheetAreaM2 in BigDecimal("0.5")..BigDecimal(10), "seed $seed area ${read.input.sheetAreaM2}")
            assertTrue(read.input.cutTimeMin in BigDecimal.ONE..BigDecimal(60), "seed $seed time ${read.input.cutTimeMin}")
            assertTrue(form.counterparty.startsWith("ООО «"), "seed $seed counterparty '${form.counterparty}'")
        }
    }

    @Test
    fun `a seed repeats its calculation and another seed gives another one`() {
        assertEquals(form(7), form(7))
        assertEquals(quick(7), quick(7))
        assertTrue(form(7) != form(8))
        assertTrue(quick(7) != quick(8))
    }

    @Test
    fun `discounts are blank or above 0 up to 15, and a run of seeds sees both`() {
        val discounts = (0 until 200).map { form(it).discount } + (0 until 200).map { quick(it).discount }
        assertTrue(discounts.any { it.isEmpty() }, "no seed without a discount")
        val given = discounts.filter { it.isNotEmpty() }.map { assertNotNull(parseDecimal(it), it) }
        assertTrue(given.isNotEmpty(), "no seed with a discount")
        assertTrue(given.all { it.signum() > 0 && it <= BigDecimal(15) }, "a discount out of 1..15: $given")
    }

    @Test
    fun `the ids of the parts and the rows come from the caller and never repeat`() {
        repeat(200) { seed ->
            var next = 100L
            val form = RandomCalculation(book, Random(seed)).calculationForm { next++ }
            val ids = ids(form)
            assertEquals(ids.size, ids.toSet().size, "seed $seed repeats an id: $ids")
            assertTrue(ids.all { it in 100 until next }, "seed $seed took an id the caller did not hand out: $ids")
        }
    }

    @Test
    fun `an operation the book has no price for is never chosen, and the forms still calculate Ok`() {
        val withoutTubeLaser = book.copy(tubeLaserCutPerCm = emptyList())
        val withoutSheetPierce = book.copy(laserSheetPiercePerPiece = emptyList())
        val withoutBending = book.copy(bendingPerBend = emptyList())
        val withoutWelding = book.copy(weldingPerCm = emptyList())
        val withoutPainting = book.copy(paintingPerM2 = null)
        repeat(200) { seed ->
            fun check(book: PriceBook, absent: OperationKind, where: String) {
                val form = form(seed, book)
                assertTrue(absent !in form.operationKinds(), "seed $seed: $absent without $where")
                assertIs<CalcOutcome.Ok>(Calculator(book).calculate(form.read().input), "seed $seed without $where")
            }
            check(withoutTubeLaser, OperationKind.TUBE_LASER, "tube laser prices")
            check(withoutSheetPierce, OperationKind.LASER_SHEET, "sheet pierce prices")
            check(withoutBending, OperationKind.BENDING, "bending prices")
            check(withoutWelding, OperationKind.WELDING, "welding prices")
            check(withoutPainting, OperationKind.PAINTING, "a painting price")
            // Holes in a tube are cut by the tube laser, holes in a sheet by the sheet laser.
            val tubeHoles = form(seed, withoutTubeLaser).parts.filter { it.material.kind != SortamentKind.SHEET }
            assertTrue(tubeHoles.none { OperationKind.HOLES in it.operations }, "seed $seed: holes in a tube without tube laser prices")
        }
    }

    @Test
    fun `a bend length falls in a range that has a price`() {
        // Only «до 100» is priced; the book has the other ranges' cells removed.
        val onlyShortBends = book.copy(bendingPerBend = book.bendingPerBend.filter { it.upToMm?.compareTo(BigDecimal(100)) == 0 })
        var bends = 0
        repeat(200) { seed ->
            val form = form(seed, onlyShortBends)
            assertIs<CalcOutcome.Ok>(Calculator(onlyShortBends).calculate(form.read().input), "seed $seed")
            form.parts.mapNotNull { it.operations[OperationKind.BENDING] as? BendingForm }.flatMap { it.rows }.forEach { row ->
                bends++
                val length = assertNotNull(parseDecimal(row.length), "seed $seed bend '${row.length}'")
                assertTrue(length.signum() > 0 && length <= BigDecimal(100), "seed $seed bend $length is outside «до 100»")
            }
        }
        assertTrue(bends > 0, "no seed bent anything")
    }

    @Test
    fun `a book with nothing to choose from leaves the choices to the editor and throws nothing`() {
        val bare = book.copy(grades = emptyList())
        repeat(20) { seed ->
            val form = form(seed, bare)
            assertTrue(form.parts.all { it.material.grade == null && it.material.size == null && it.operations.isEmpty() }, "seed $seed")
            assertTrue(form.read().errors.isNotEmpty(), "seed $seed")
            val quick = quick(seed, bare)
            assertNull(quick.grade)
            assertNull(quick.thicknessMm)
            assertTrue(quick.read().errors.isNotEmpty(), "seed $seed")
        }
    }
}
