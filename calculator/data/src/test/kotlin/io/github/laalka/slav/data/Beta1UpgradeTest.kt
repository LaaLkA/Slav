package io.github.laalka.slav.data

import io.github.laalka.slav.core.BendRow
import io.github.laalka.slav.core.CalcOutcome
import io.github.laalka.slav.core.CalculationInput
import io.github.laalka.slav.core.Calculator
import io.github.laalka.slav.core.HoleRow
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.OperationKind
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.core.ProductOperation
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Calculations and settings saved by the released 1.0.0-beta.1 must still open. The files under
 * resources/beta1 are the text that program stored, written by running its own code (the model
 * before parts lost their quantity, sheets got shapes and welding and painting moved to the
 * product), not hand-edited: [calculation] has every feature that changed.
 */
class Beta1UpgradeTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val paths by lazy { AppPaths(dir.resolve("data")) }
    private lateinit var db: AppDatabase
    private lateinit var calculations: CalculationRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(paths, clock).open()
        calculations = CalculationRepository(db, clock)
    }

    @AfterTest
    fun close() = db.close()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/beta1/$name")) { "Fixture $name is missing" }.readText()

    private fun extra(amount: String, comment: String) = ManualExtra(Money(dec(amount)), comment)

    /** Puts rows into the table the way beta.1 left them: the text of its columns, as it was. */
    private fun insertBeta1(status: CalculationStatus, quantity: Int, input: String, snapshot: String?, result: String?, unitPrice: String?): UUID {
        val id = UUID.randomUUID()
        db.database.calculationQueries.insert(
            id = id.toString(),
            name = "Из beta.1",
            nameSearch = "из beta.1",
            status = status.name,
            quantity = quantity.toLong(),
            unitPrice = unitPrice,
            createdAt = clock.millis(),
            updatedAt = clock.millis(),
            snapshotAt = if (snapshot != null) clock.millis() else null,
            inputJson = input,
            snapshotJson = snapshot,
            resultJson = result,
            sourceId = null,
            kind = CalculationKind.FULL.name,
        )
        return id
    }

    private fun insertCalculation(): UUID = insertBeta1(
        CalculationStatus.CALCULATED,
        quantity = 25,
        input = fixture("calculation-input.json"),
        snapshot = fixture("calculation-snapshot.json"),
        result = fixture("calculation-result.json"),
        unitPrice = fixture("calculation-unit-price.txt"),
    )

    /** The unit price the beta.1 code stored for the fixture: 3 × 6079.94 + 2 × 2732.63 + 5351.00 + 1561.00 + 691.54. */
    private val oldUnitPrice get() = BigDecimal(fixture("calculation-unit-price.txt"))

    @Test
    fun `the fixtures are the beta1 text, not text of the current model`() {
        val input = fixture("calculation-input.json")
        for (oldForm in listOf("\"$LEGACY_PART_QUANTITY\":3", "\"lengthMm\":\"400\",\"widthMm\":\"250\"", "\"mode\":{\"type\":\"rectangle\"}", "\"type\":\"welding\"", "\"type\":\"painting\"", "\"rows\":[{\"lengthMm\":\"80\",\"count\":6}")) {
            assertTrue(oldForm in input, "$oldForm is not in the fixture input")
        }
        assertTrue("\"$LEGACY_PART_QUANTITY\":3" in fixture("calculation-result.json"))
        assertEquals(1, Json.parseToJsonElement(fixture("calculation-snapshot.json")).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(1, Json.parseToJsonElement(fixture("settings-export-format1.json")).jsonObject.getValue("formatVersion").jsonPrimitive.int)
    }

    @Test
    fun `a part with quantity n becomes n parts, sheets get a rectangle, welding and painting of a part are dropped`() {
        val stored = assertNotNull(calculations.get(insertCalculation()))

        val base = Part(
            name = "Основание",
            material = Material.Sheet("Ст3", dec("3"), SheetShape.Rectangle(dec("400"), dec("250"))),
            operations = listOf(
                PartOperation.LaserSheet(SheetCutMode.ByShape, extra("150", "Снятие фаски")),
                PartOperation.Holes(listOf(HoleRow(dec("8.5"), count = 4))),
                PartOperation.Bending(listOf(BendRow(dec("80"), 2))),
            ),
            materialExtra = extra("200.50", "Доставка листа"),
        )
        val post = Part(
            name = "Стойка",
            material = Material.RoundTube("Ст3", diameterMm = dec("57"), wallMm = dec("3"), lengthMm = dec("1000")),
            operations = listOf(PartOperation.TubeLaser(), PartOperation.Holes(listOf(HoleRow(dec("10"), count = 2, throughBothWalls = true)))),
        )
        val plate = Part(
            name = "Пластина",
            material = Material.Sheet("Ст3", dec("2"), SheetShape.Rectangle(dec("1000"), dec("500"))),
            operations = listOf(PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("1840.5"), pierces = 3))),
        )
        val expected = CalculationInput(
            name = "Кронштейн КР-12",
            quantity = 25,
            parts = listOf(base, base, base, post, post, plate),
            productOperations = listOf(
                // 6 × 80 + 2 × 40.5 = 561 mm of seams.
                ProductOperation.AssemblyWelding("полуавтомат", "Сталь", dec("3"), seamLengthMm = dec("561"), manualExtra = extra("1000", "Зачистка швов")),
                ProductOperation.Painting(),
            ),
        )
        assertEquals(expected, stored.input)
    }

    @Test
    fun `the stored result decodes and its parts follow the upgraded input, so the old price stays`() {
        val stored = assertNotNull(calculations.get(insertCalculation()))
        val result = assertNotNull(stored.result)

        assertEquals(stored.input.parts.map { it.name }, result.parts.map { it.name })
        assertEquals(listOf("Основание", "Основание", "Основание", "Стойка", "Стойка", "Пластина"), result.parts.map { it.name })
        // The price the old program showed: 3 × 6079.94 + 2 × 2732.63 + 5351.00 + (1561.00 + 691.54).
        assertEquals(0, oldUnitPrice.compareTo(result.unitPrice.amount), "unit price ${result.unitPrice.amount}")
        assertEquals(0, oldUnitPrice.compareTo(assertNotNull(stored.unitPrice).amount))
        assertEquals(0, oldUnitPrice.compareTo(assertNotNull(calculations.list().single().unitPrice).amount))
        assertEquals(0, BigDecimal(fixture("calculation-unit-mass.txt")).compareTo(result.unitMassKg), "unit mass ${result.unitMassKg}")
        // The lines the current model no longer has on a part are kept in the result, which is what was priced.
        assertEquals(
            listOf(OperationKind.LASER_SHEET, OperationKind.HOLES, OperationKind.BENDING, OperationKind.WELDING, OperationKind.PAINTING),
            result.parts.first().operations.map { it.kind },
        )
    }

    @Test
    fun `the snapshot of beta1 decodes and takes the default painting minimum`() {
        val snapshot = assertNotNull(assertNotNull(calculations.get(insertCalculation())).snapshot)

        assertEquals(1, snapshot.formatVersion)
        assertEquals(0, BigDecimal("7850").compareTo(snapshot.grades.single().densityKgM3))
        assertEquals(0, BigDecimal("0.1").compareTo(snapshot.general.minPaintAreaPerPartM2))
    }

    @Test
    fun `the upgraded input prices on the old snapshot as the old price without the welding and painting of parts`() {
        val stored = assertNotNull(calculations.get(insertCalculation()))

        val outcome = Calculator(assertNotNull(stored.snapshot)).calculate(stored.input)

        val priced = assertIs<CalcOutcome.Ok>(outcome, "the upgraded input must be priceable: $outcome").result
        // Dropped per piece: «Основание» welding 480.00 + painting 262.50 (× 3), «Стойка» painting 62.67 (× 2), «Пластина» welding 100.00.
        // 31308.62 − (3 × 742.50 + 2 × 62.67 + 100.00) = 28855.78. The assembly welding and the product painting are unchanged.
        assertEquals("28855.78", priced.unitPrice.amount.toPlainString())
        assertEquals(stored.result?.productLines?.map { it.cost }, priced.productLines.map { it.cost })
    }

    @Test
    fun `a draft with an unfilled quantity keeps its part`() {
        val id = insertBeta1(CalculationStatus.DRAFT, quantity = 1, input = fixture("draft-input.json"), snapshot = null, result = null, unitPrice = null)

        val draft = assertNotNull(calculations.get(id))

        assertEquals(
            listOf(
                Part(
                    name = "Деталь 1",
                    material = Material.Sheet("", dec("0"), SheetShape.Rectangle(dec("0"), dec("0"))),
                    operations = listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)),
                ),
            ),
            draft.input.parts,
        )
        assertEquals(listOf(ProductOperation.AssemblyWelding("", "", dec("0"), dec("0"))), draft.input.productOperations)
    }

    @Test
    fun `a revision of an upgraded calculation is stored in the current form and the old row stays as written`() {
        val id = insertCalculation()
        val written = db.database.calculationQueries.selectById(id.toString()).executeAsOne()
        val source = assertNotNull(calculations.get(id))

        val revision = calculations.createRevision(
            id,
            source.input.copy(quantity = 40),
            assertNotNull(source.snapshot),
            assertNotNull(source.result),
            snapshotAt = source.snapshotAt,
        )

        val row = db.database.calculationQueries.selectById(revision.toString()).executeAsOne()
        assertTrue(LEGACY_PART_QUANTITY !in row.input_json && "\"seamLengthMm\":\"561\"" in row.input_json, row.input_json)
        // The run size is the only thing that changed: the result stays the one beta.1 calculated.
        val stored = assertNotNull(calculations.get(revision))
        assertEquals(40, stored.quantity)
        assertEquals(source.result, stored.result)
        assertEquals(0, oldUnitPrice.compareTo(assertNotNull(stored.result).unitPrice.amount))
        assertEquals(written, db.database.calculationQueries.selectById(id.toString()).executeAsOne(), "the beta.1 row is not rewritten")
    }

    @Test
    fun `an input of the current model passes through the upgrade unchanged`() {
        val input = sheetInput("Кронштейн").let {
            it.copy(
                parts = it.parts + Part("Диск", Material.Sheet("Ст3", dec("2"), SheetShape.Circle(dec("50"))), emptyList(), massOverrideKg = dec("1.5")),
                productOperations = listOf(ProductOperation.AssemblyWelding("полуавтомат", "Сталь", dec("2"), dec("120")), ProductOperation.Painting(dec("2"))),
            )
        }
        val json = Json.parseToJsonElement(Json.encodeToString(CalculationInput.serializer(), input))

        assertEquals(json, upgradeInputJson(json))
        assertEquals(input, Json.decodeFromJsonElement(CalculationInput.serializer(), upgradeInputJson(json)))
    }

    @Test
    fun `a sheet set by weight is not taken for a beta1 sheet and passes through the upgrade unchanged`() {
        val sheet = Material.Sheet("Ст3", dec("3"), SheetShape.ByMass(dec("12"), dec("3000")))
        val input = sheetInput("Пластина").let { it.copy(parts = it.parts + Part("По весу", sheet, listOf(PartOperation.LaserSheet(SheetCutMode.ByShape)))) }
        val json = Json.parseToJsonElement(Json.encodeToString(CalculationInput.serializer(), input))
        val text = json.toString()
        assertTrue("""{"type":"by_mass","massKg":"12","perimeterMm":"3000"}""" in text, text)

        assertEquals(json, upgradeInputJson(json))
        assertEquals(input, Json.decodeFromJsonElement(CalculationInput.serializer(), upgradeInputJson(json)))
    }

    private fun upgradedWelding(vararg rows: String): JsonObject {
        val operation = """{"type":"assembly_welding","weldType":"т","group":"г","thicknessMm":"2","rows":[${rows.joinToString(",")}],"manualExtra":null}"""
        val upgraded = upgradeInputJson(Json.parseToJsonElement("""{"name":"н","quantity":1,"parts":[],"productOperations":[$operation]}"""))
        return upgraded.jsonObject.getValue("productOperations").jsonArray.single().jsonObject
    }

    @Test
    fun `the seam length is the sum of length times count of the old rows, without trailing zeros`() {
        // 100 × 1 + 25.5 × 2 = 151, not 151.0; 10 × 10 = 100, not 1E+2; no rows is no seam.
        assertEquals("151", upgradedWelding("""{"lengthMm":"100","count":1}""", """{"lengthMm":"25.5","count":2}""").getValue("seamLengthMm").jsonPrimitive.content)
        assertEquals("100", upgradedWelding("""{"lengthMm":"10","count":10}""").getValue("seamLengthMm").jsonPrimitive.content)
        assertEquals("0", upgradedWelding().getValue("seamLengthMm").jsonPrimitive.content)
        assertFalse("rows" in upgradedWelding("""{"lengthMm":"10","count":1}"""))
    }

    @Test
    fun `a settings export of format 1 still imports and is exported again as format 3`() {
        val settings = SettingsRepository(db, clock)
        val transfer = SettingsTransfer(settings, paths, appVersion = "1.1.0", clock = clock)
        val file = dir.resolve("beta1-settings.json").also { it.writeText(fixture("settings-export-format1.json")) }

        val imported = transfer.read(file)

        assertEquals(1, imported.formatVersion)
        assertEquals("1.0.0-beta.1", imported.appVersion)
        // The old file has none of the new settings: they take their defaults, and what it has is read as it was.
        assertEquals(0, BigDecimal("0.1").compareTo(imported.settings.general.minPaintAreaPerPartM2))
        assertEquals(0, BigDecimal("7850").compareTo(imported.settings.grades.single().densityKgM3))
        assertEquals(0, BigDecimal("350").compareTo(assertNotNull(imported.settings.paintingPerM2)))
        assertNull(imported.settings.laserCutPerMinute)
        assertTrue(transfer.previewReplace(imported.settings).hasChanges)

        val backup = transfer.replace(imported.settings)
        assertTrue(backup.exists())
        assertEquals(imported.settings, settings.load())

        val exported = dir.resolve("exported-again.json").also(transfer::export)
        // Written as the current format, which an older program refuses with «обновите программу».
        assertEquals(3, PriceBook.FORMAT_VERSION)
        assertEquals(3, Json.parseToJsonElement(exported.readText()).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(imported.settings.copy(formatVersion = 3), transfer.read(exported).settings)
    }
}
