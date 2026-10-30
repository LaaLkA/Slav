package io.github.laalka.slav.data

import io.github.laalka.slav.core.BendRow
import io.github.laalka.slav.core.HoleRow
import io.github.laalka.slav.core.ManualExtra
import io.github.laalka.slav.core.Material
import io.github.laalka.slav.core.Money
import io.github.laalka.slav.core.Part
import io.github.laalka.slav.core.PartOperation
import io.github.laalka.slav.core.SheetCutMode
import io.github.laalka.slav.core.SheetShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartTemplateRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private lateinit var db: AppDatabase
    private lateinit var templates: PartTemplateRepository

    @BeforeTest
    fun open() {
        db = DatabaseFactory(AppPaths(dir), clock).open()
        templates = PartTemplateRepository(db, clock)
    }

    @AfterTest
    fun close() = db.close()

    private val plate = sheetInput("Пластина").parts.single()

    private val post = Part(
        name = "Стойка",
        material = Material.RoundTube("Ст3", diameterMm = dec("57"), wallMm = dec("3"), lengthMm = dec("1000")),
        operations = listOf(
            PartOperation.TubeLaser(cuts = 2),
            PartOperation.Holes(listOf(HoleRow(dec("10"), count = 2, throughBothWalls = true))),
            PartOperation.Bending(listOf(BendRow(dec("80"), 4))),
        ),
        massOverrideKg = dec("4.2"),
    )

    private fun written(result: TemplateWriteResult): PartTemplate = assertIs<TemplateWriteResult.Written>(result).template

    private fun taken(result: TemplateWriteResult): PartTemplate = assertIs<TemplateWriteResult.NameTaken>(result).existing

    private fun names(search: String = "") = templates.list(search).map { it.name }

    @Test
    fun `a sheet set by weight is stored and read back with and without its perimeter`() {
        for (shape in listOf(SheetShape.ByMass(dec("12"), dec("3000")), SheetShape.ByMass(dec("12.5")))) {
            val part = Part(
                name = "По весу",
                material = Material.Sheet("Ст3", dec("3"), shape),
                operations = listOf(PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("1200"), pierces = 2))),
            )

            val saved = written(templates.save("Пластина ${shape.perimeterMm}", part))

            assertEquals(part, assertNotNull(templates.get(saved.id)).part)
            assertEquals(shape, assertIs<Material.Sheet>(assertNotNull(templates.get(saved.id)).part.material).shape)
        }
    }

    @Test
    fun `a saved part is read back whole, with the dates of the save`() {
        val saved = written(templates.save("Кронштейн", plate))

        assertEquals("Кронштейн", saved.name)
        assertEquals(plate, saved.part)
        assertEquals(clock.now, saved.createdAt)
        assertEquals(clock.now, saved.updatedAt)
        assertEquals(saved, templates.get(saved.id))
        assertEquals(listOf(saved), templates.list())
        assertNull(templates.get(UUID.randomUUID()))
    }

    @Test
    fun `every kind of part and every extra survives the round trip`() {
        val full = plate.copy(
            massOverrideKg = dec("1.25"),
            operations = plate.operations + listOf(
                PartOperation.LaserSheet(SheetCutMode.ContourLength(dec("1840.5"), pierces = 3), ManualExtra(Money(dec("12.50")), "Фаска")),
                PartOperation.Holes(listOf(HoleRow(dec("8.5"), count = 4))),
            ),
        )
        val parts = listOf(plate, post, full, full.copy(material = Material.Sheet("Ст3", dec("2"), SheetShape.Ring(dec("50"), dec("20")))))

        val ids = parts.mapIndexed { index, part -> written(templates.save("Деталь $index", part)).id }

        ids.forEachIndexed { index, id -> assertEquals(parts[index], assertNotNull(templates.get(id)).part) }
    }

    @Test
    fun `the search finds a name by a part of it in any letter case, Cyrillic included`() {
        templates.save("Кронштейн", plate)
        templates.save("Опора КРОНА", post)
        templates.save("Рама", plate)

        assertEquals(listOf("Кронштейн", "Опора КРОНА"), names("КРОН"))
        assertEquals(listOf("Кронштейн", "Опора КРОНА"), names("крон"))
        assertEquals(listOf("Кронштейн"), names("  ШТЕЙН "))
        assertEquals(emptyList(), names("болт"))
        assertEquals(listOf("Кронштейн", "Опора КРОНА", "Рама"), names(""))
        assertEquals(listOf("Кронштейн", "Опора КРОНА", "Рама"), names("   "))
    }

    @Test
    fun `the search text is literal, so percent and underscore match themselves`() {
        templates.save("Лист_2", plate)
        templates.save("Лист 2", plate)
        templates.save("Скос 45%", plate)

        assertEquals(listOf("Лист_2"), names("_"))
        assertEquals(listOf("Скос 45%"), names("%"))
        assertEquals(emptyList(), names("Лист%2"))
    }

    @Test
    fun `the list is in the order of the names the Russian way, letter case aside`() {
        listOf("Яблоко", "арка", "Балка", "Ёмкость", "бобышка").forEach { templates.save(it, plate) }

        assertEquals(listOf("арка", "Балка", "бобышка", "Ёмкость", "Яблоко"), names())
    }

    @Test
    fun `a name that is taken is refused, trimmed and in any letter case, and nothing is changed`() {
        val first = written(templates.save("Кронштейн", plate))
        clock.advance(Duration.ofMinutes(5))

        for (name in listOf("Кронштейн", "кронштейн", "  КРОНШТЕЙН\t")) {
            assertEquals(first, taken(templates.save(name, post)), "'$name'")
        }

        assertEquals(listOf(first), templates.list())
        assertEquals(plate, assertNotNull(templates.get(first.id)).part)
    }

    @Test
    fun `overwrite replaces the part of the template of that name and keeps its id, its name and its creation date`() {
        val first = written(templates.save("Кронштейн", plate))
        clock.advance(Duration.ofMinutes(5))

        val replaced = written(templates.save("  кронштейн ", post, overwrite = true))

        assertEquals(first.id, replaced.id)
        assertEquals("Кронштейн", replaced.name, "the template keeps the name it has, whatever letter case was typed")
        assertEquals(post, replaced.part)
        assertEquals(first.createdAt, replaced.createdAt)
        assertEquals(clock.now, replaced.updatedAt)
        assertEquals(listOf(replaced), templates.list())
        assertEquals(replaced, templates.get(first.id))
        // Nothing to replace: overwrite saves like a plain save.
        val other = written(templates.save("Опора", plate, overwrite = true))
        assertNotEquals(first.id, other.id)
        assertEquals(2, templates.list().size)
    }

    @Test
    fun `a blank name is refused and nothing is stored`() {
        assertFailsWith<IllegalArgumentException> { templates.save("", plate) }
        assertFailsWith<IllegalArgumentException> { templates.save("  \t ", plate, overwrite = true) }

        assertEquals(emptyList(), templates.list())
    }

    @Test
    fun `rename gives another name to the same template and the old name is free again`() {
        val first = written(templates.save("Кронштейн", plate))
        clock.advance(Duration.ofMinutes(5))

        val renamed = written(templates.rename(first.id, "  Кронштейн КР-12 "))

        assertEquals(first.id, renamed.id)
        assertEquals("Кронштейн КР-12", renamed.name)
        assertEquals(plate, renamed.part)
        assertEquals(first.createdAt, renamed.createdAt)
        assertEquals(clock.now, renamed.updatedAt)
        assertEquals(renamed, templates.get(first.id))
        assertEquals(listOf("Кронштейн КР-12"), names("кр-12"))
        // The search text follows the new name: the old one finds nothing, and may be taken again.
        assertEquals(emptyList(), names("кронштейн кр-12 х"))
        assertIs<TemplateWriteResult.Written>(templates.save("Кронштейн", post))
    }

    @Test
    fun `rename refuses a name another template has, but accepts a change of the letter case of its own`() {
        val first = written(templates.save("Кронштейн", plate))
        val second = written(templates.save("Опора", post))

        assertEquals(second, taken(templates.rename(first.id, " опора ")))
        assertEquals(listOf("Кронштейн", "Опора"), names())

        assertEquals("КРОНШТЕЙН", written(templates.rename(first.id, "КРОНШТЕЙН")).name)
        assertEquals("КРОНШТЕЙН", assertNotNull(templates.get(first.id)).name)
        assertEquals("Кронштейн", written(templates.rename(first.id, "Кронштейн")).name)
    }

    @Test
    fun `rename refuses a blank name and a template that is gone`() {
        val first = written(templates.save("Кронштейн", plate))

        assertFailsWith<IllegalArgumentException> { templates.rename(first.id, "  ") }
        assertFailsWith<NoSuchElementException> { templates.rename(UUID.randomUUID(), "Опора") }

        assertEquals("Кронштейн", assertNotNull(templates.get(first.id)).name)
    }

    @Test
    fun `delete removes only that template, and its name is free again`() {
        val first = written(templates.save("Кронштейн", plate))
        val second = written(templates.save("Опора", post))

        templates.delete(first.id)

        assertNull(templates.get(first.id))
        assertEquals(listOf(second), templates.list())
        templates.delete(first.id)
        assertEquals(listOf(second), templates.list())
        assertIs<TemplateWriteResult.Written>(templates.save("Кронштейн", plate))
    }

    @Test
    fun `the library is kept when the database is opened again`() {
        val saved = written(templates.save("Кронштейн", plate))
        db.close()

        db = DatabaseFactory(AppPaths(dir), clock).open()
        templates = PartTemplateRepository(db, clock)

        assertEquals(listOf(saved), templates.list())
    }

    @Test
    fun `the database itself refuses two templates whose names differ in letter case only`() {
        val queries = db.database.partTemplateQueries
        queries.insert(UUID.randomUUID().toString(), "Кронштейн", "кронштейн", "{}", clock.millis(), clock.millis())

        assertFails { queries.insert(UUID.randomUUID().toString(), "КРОНШТЕЙН", "кронштейн", "{}", clock.millis(), clock.millis()) }
    }

    @Test
    fun `a part stored by beta1 is read in the current model`() {
        val stored = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/beta1/calculation-input.json")) { "The beta1 input fixture is missing" }.readText(),
        ).jsonObject.getValue("parts").jsonArray.first().toString()
        // The text is the old form: a quantity, a sheet with a length and a width, the old laser mode.
        for (oldForm in listOf("\"$LEGACY_PART_QUANTITY\":3", "\"lengthMm\":\"400\",\"widthMm\":\"250\"", "\"mode\":{\"type\":\"rectangle\"}")) {
            assertTrue(oldForm in stored, "$oldForm is not in the old part")
        }
        val id = UUID.randomUUID()
        db.database.partTemplateQueries.insert(id.toString(), "Основание", "основание", stored, clock.millis(), clock.millis())

        val template = assertNotNull(templates.get(id))

        // One part, not three copies of it: a template is one piece.
        assertEquals(
            Part(
                name = "Основание",
                material = Material.Sheet("Ст3", dec("3"), SheetShape.Rectangle(dec("400"), dec("250"))),
                operations = listOf(
                    PartOperation.LaserSheet(SheetCutMode.ByShape, ManualExtra(Money(dec("150")), "Снятие фаски")),
                    PartOperation.Holes(listOf(HoleRow(dec("8.5"), count = 4))),
                    PartOperation.Bending(listOf(BendRow(dec("80"), 2))),
                ),
                materialExtra = ManualExtra(Money(dec("200.50")), "Доставка листа"),
            ),
            template.part,
        )
        assertEquals(listOf(template), templates.list("основание"))
    }
}
