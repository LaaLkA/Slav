package io.github.laalka.slav.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons of the navigation rail, drawn as in the mockups: 24 units, a 2-unit line (Lucide's «list», «layout-grid»
 * and «settings»). They are lines with round ends, so the dots of the list are dots; the colour is the one the
 * item gives its icon.
 */
internal object RailIcons {
    val Calculations: ImageVector by lazy { lineIcon("Calculations", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }

    val Library: ImageVector by lazy { lineIcon("Library", "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z") }

    val Settings: ImageVector by lazy {
        lineIcon(
            "Settings",
            "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z" +
                "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1" +
                "a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3" +
                "a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9" +
                "a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1" +
                "a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
}

private const val ICON_SIZE = 22
private const val VIEWPORT = 24f
private const val LINE = 2f

private fun lineIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, ICON_SIZE.dp, ICON_SIZE.dp, VIEWPORT, VIEWPORT).addPath(
        pathData = addPathNodes(path),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = LINE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
