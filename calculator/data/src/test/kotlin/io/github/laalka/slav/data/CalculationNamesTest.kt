package io.github.laalka.slav.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure rules that name a copy «<base> (N)» and a revision «<base> №N»: the smallest N from 2 that is free. */
class CalculationNamesTest {
    private fun taken(vararg names: String): (String) -> Boolean = { it in names }

    @Test
    fun `a trailing copy number is the only thing stripped to find the base of a copy`() {
        assertEquals("Кронштейн", copyBaseName("Кронштейн"))
        assertEquals("Кронштейн", copyBaseName("Кронштейн (2)"))
        assertEquals("Кронштейн", copyBaseName("Кронштейн (12)"))
        assertEquals("Кронштейн", copyBaseName("  Кронштейн (3) "))
        // Only one level: «Рама (2) (3)» is a copy of «Рама (2)».
        assertEquals("Рама (2)", copyBaseName("Рама (2) (3)"))
    }

    @Test
    fun `a name that merely ends in a number or has brackets elsewhere has no copy number`() {
        assertEquals("Деталь 2", copyBaseName("Деталь 2"))
        assertEquals("Деталь 12", copyBaseName("Деталь 12"))
        assertEquals("Деталь (2) чертёж", copyBaseName("Деталь (2) чертёж"))
        assertEquals("Деталь(2)", copyBaseName("Деталь(2)"))
        assertEquals("Деталь (б)", copyBaseName("Деталь (б)"))
        assertEquals("Деталь ()", copyBaseName("Деталь ()"))
        assertEquals("(2)", copyBaseName("(2)"))
        assertEquals("Кронштейн №2", copyBaseName("Кронштейн №2"))
    }

    @Test
    fun `a trailing revision number is the only thing stripped to find the base of a revision`() {
        assertEquals("Кронштейн", revisionBaseName("Кронштейн"))
        assertEquals("Кронштейн", revisionBaseName("Кронштейн №2"))
        assertEquals("Кронштейн", revisionBaseName("Кронштейн №15"))
        assertEquals("Деталь 2", revisionBaseName("Деталь 2"))
        assertEquals("Кронштейн № 3", revisionBaseName("Кронштейн № 3"))
        assertEquals("Кронштейн №3 чертёж", revisionBaseName("Кронштейн №3 чертёж"))
        assertEquals("№3", revisionBaseName("№3"))
        assertEquals("Кронштейн (2)", revisionBaseName("Кронштейн (2)"))
        assertTrue(hasRevisionNumber("Кронштейн №2"))
        assertFalse(hasRevisionNumber("Кронштейн"))
        assertFalse(hasRevisionNumber("Деталь 2"))
    }

    @Test
    fun `a copy takes the smallest free number from 2`() {
        assertEquals("Кронштейн (2)", nextCopyName("Кронштейн", taken()))
        assertEquals("Кронштейн (3)", nextCopyName("Кронштейн", taken("Кронштейн (2)")))
        assertEquals("Кронштейн (4)", nextCopyName("Кронштейн", taken("Кронштейн (2)", "Кронштейн (3)")))
        // A gap is filled before the sequence goes on.
        assertEquals("Кронштейн (3)", nextCopyName("Кронштейн", taken("Кронштейн (2)", "Кронштейн (4)")))
        // The name of the source itself is not a candidate: the numbers start at 2 whatever the source holds.
        assertEquals("Кронштейн (2)", nextCopyName("Кронштейн", taken("Кронштейн")))
    }

    @Test
    fun `copying a copy goes on from the base, not from the number of the copy`() {
        assertEquals("Кронштейн (3)", nextCopyName("Кронштейн (2)", taken("Кронштейн", "Кронштейн (2)")))
        assertEquals("Кронштейн (2)", nextCopyName("Кронштейн (5)", taken("Кронштейн", "Кронштейн (5)")))
        assertEquals("Кронштейн (4)", nextCopyName("Кронштейн (3)", taken("Кронштейн (2)", "Кронштейн (3)")))
    }

    @Test
    fun `a name ending in a digit is a base of its own, not a copy number`() {
        assertEquals("Деталь 2 (2)", nextCopyName("Деталь 2", taken("Деталь 2")))
        assertEquals("Деталь 2 (3)", nextCopyName("Деталь 2", taken("Деталь 2", "Деталь 2 (2)")))
        assertEquals("Деталь 2 №2", nextRevisionName("Деталь 2", taken("Деталь 2")))
        assertEquals("Деталь 12 (2)", nextCopyName("Деталь 12", taken()))
    }

    @Test
    fun `a revision takes the smallest free number from 2 after the base of its source`() {
        assertEquals("Кронштейн №2", nextRevisionName("Кронштейн", taken("Кронштейн")))
        assertEquals("Кронштейн №3", nextRevisionName("Кронштейн", taken("Кронштейн", "Кронштейн №2")))
        assertEquals("Кронштейн №3", nextRevisionName("Кронштейн №2", taken("Кронштейн", "Кронштейн №2")))
        assertEquals("Кронштейн №3", nextRevisionName("Кронштейн", taken("Кронштейн №2", "Кронштейн №4")))
        assertEquals("Кронштейн №2", nextRevisionName("Кронштейн №7", taken("Кронштейн №7")))
    }

    @Test
    fun `the two numberings do not mix`() {
        assertEquals("Кронштейн №2 (2)", nextCopyName("Кронштейн №2", taken("Кронштейн №2")))
        assertEquals("Кронштейн (2) №2", nextRevisionName("Кронштейн (2)", taken("Кронштейн (2)")))
        // A copy's number does not take a revision's.
        assertEquals("Кронштейн №2", nextRevisionName("Кронштейн", taken("Кронштейн (2)")))
        assertEquals("Кронштейн (2)", nextCopyName("Кронштейн", taken("Кронштейн №2")))
    }

    @Test
    fun `a blank name is not numbered, it stays as it was`() {
        assertEquals("", nextCopyName("", taken()))
        assertEquals("  ", nextCopyName("  ", taken()))
        assertEquals("", nextRevisionName("", taken()))
    }

    @Test
    fun `outer spaces of the source are not carried into the new name`() {
        assertEquals("Кронштейн (2)", nextCopyName("  Кронштейн ", taken()))
        assertEquals("Кронштейн №2", nextRevisionName(" Кронштейн  ", taken()))
    }
}
