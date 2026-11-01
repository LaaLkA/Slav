package io.github.laalka.slav.app.ui

import io.github.laalka.slav.app.developer.Added
import io.github.laalka.slav.app.settings.message
import kotlin.test.Test
import kotlin.test.assertEquals

class PluralTest {
    private fun marks(count: Int) = pluralRu(count, "марка", "марки", "марок")

    @Test
    fun `the word takes the form Russian asks for the count`() {
        mapOf(
            0 to "0 марок", 1 to "1 марка", 2 to "2 марки", 3 to "3 марки", 4 to "4 марки", 5 to "5 марок",
            9 to "9 марок", 10 to "10 марок", 11 to "11 марок", 12 to "12 марок", 14 to "14 марок", 15 to "15 марок",
            20 to "20 марок", 21 to "21 марка", 22 to "22 марки", 25 to "25 марок", 100 to "100 марок", 101 to "101 марка",
            111 to "111 марок", 112 to "112 марок", 121 to "121 марка", 769 to "769 марок", 1001 to "1001 марка",
        ).forEach { (count, expected) -> assertEquals(expected, marks(count), "count $count") }
    }

    @Test
    fun `the confirmation of a fill names the three counts in their own forms`() {
        assertEquals("Добавлено: 4 марки, 85 размеров, 769 цен", Added(grades = 4, sizes = 85, prices = 769).message())
        assertEquals("Добавлено: 1 марка, 0 размеров, 25 цен", Added(grades = 1, sizes = 0, prices = 25).message())
        assertEquals("Добавлено: 21 марка, 1 размер, 1 цена", Added(grades = 21, sizes = 1, prices = 1).message())
        assertEquals("Добавлено: 2 марки, 22 размера, 3 цены", Added(grades = 2, sizes = 22, prices = 3).message())
        assertEquals("Добавлено: 11 марок, 12 размеров, 14 цен", Added(grades = 11, sizes = 12, prices = 14).message())
    }
}
