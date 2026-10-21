package io.github.laalka.slav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun `rounds half up to kopecks`() {
        assertMoney("10.01", Money(dec("10.005")).rounded())
        assertMoney("10.00", Money(dec("10.004")).rounded())
        // HALF_EVEN would give 0.12.
        assertMoney("0.13", Money(dec("0.125")).rounded())
        assertMoney("-1.01", Money(dec("-1.005")).rounded())
    }

    @Test
    fun `rounded amount always has two decimals`() {
        assertEquals("5.00", Money(dec("5")).rounded().amount.toPlainString())
        assertEquals("7.85", Money(dec("7.8500000")).rounded().amount.toPlainString())
    }

    @Test
    fun `arithmetic is exact`() {
        assertMoney("0.3", listOf(Money(dec("0.1")), Money(dec("0.2"))).sum())
        assertMoney("0.000003", Money(dec("0.000001")) * 3)
        assertMoney("24.6913578", Money(dec("12.3456789")) * dec("2"))
        assertMoney("0", emptyList<Money>().sum())
    }
}
