package io.github.laalka.slav.app.ui

import io.github.laalka.slav.app.icons.renderLogo
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The logo is drawn 5 times its 96 units: a unit of the mockup is 5 pixels here.
private const val SIDE = 480
private const val UNIT = SIDE / 96

private const val ORANGE = 0xFFEA580C.toInt()
private const val INK = 0xFF0F172A.toInt()

private fun BufferedImage.alpha(x: Int, y: Int) = getRGB(x, y) ushr 24

/** The pixels of the letter: the dark ink, which the orange plate does not contain. */
private fun BufferedImage.ink(): List<Pair<Int, Int>> =
    (0 until height).flatMap { y -> (0 until width).map { x -> x to y } }.filter { (x, y) -> getRGB(x, y) == INK }

class SlavLogoTest {
    private val logo = renderLogo(SIDE)

    @Test
    fun `the plate is orange, with its corners rounded by 22 of 96 of its side`() {
        assertEquals(ORANGE, logo.getRGB(SIDE / 2, 2), "the top edge")
        assertEquals(ORANGE, logo.getRGB(2, SIDE / 2), "the left edge")
        assertEquals(0, logo.alpha(0, 0), "the very corner is not part of the plate")
        // On the arc of the corner (radius 22 units, centre 22 units in) at 45° the plate starts 6.44 units in from both sides.
        assertEquals(0, logo.alpha(5 * UNIT, 5 * UNIT), "a point outside the arc is transparent")
        assertEquals(255, logo.alpha(8 * UNIT, 8 * UNIT), "a point inside the arc is the plate")
    }

    @Test
    fun `the letter is the ink of the design and stands on its baseline in the middle of the plate`() {
        val ink = logo.ink()
        assertTrue(ink.size > SIDE * SIDE / 20, "the letter S is missing: ${ink.size} ink pixels")
        val left = ink.minOf { it.first }
        val right = ink.maxOf { it.first }
        val top = ink.minOf { it.second }
        val bottom = ink.maxOf { it.second }
        // Centred by its advance on x = 48, so its ink is centred within a unit and a half.
        assertEquals(SIDE / 2.0, (left + right) / 2.0, 1.5 * UNIT, "the letter is off centre: $left..$right")
        // On the baseline y = 72, a round letter overshooting it by about a unit.
        assertEquals(72.0 * UNIT, bottom.toDouble(), 2.0 * UNIT, "the letter is off its baseline: bottom at $bottom")
        // 70 units of body make a capital about 50 units tall.
        val height = (bottom - top + 1.0) / UNIT
        assertTrue(height in 45.0..56.0, "the letter is $height units tall")
    }

    @Test
    fun `a small logo is the same drawing`() {
        val small = renderLogo(40)
        assertEquals(ORANGE, small.getRGB(20, 1), "the top edge")
        assertEquals(0, small.alpha(0, 0), "the corner")
        val ink = small.ink()
        assertTrue(ink.isNotEmpty(), "no letter at 40 px")
        assertEquals(20.0, (ink.minOf { it.first } + ink.maxOf { it.first }) / 2.0, 1.5, "the letter at 40 px is off centre")
    }
}
