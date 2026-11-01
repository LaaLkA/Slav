package io.github.laalka.slav.app.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A box with a lid and an arrow pointing down into it: «В архив». The Material «archive» icon, which the
 * core icon set does not carry; the extended set that does is about 30 MB of the installer for this one glyph.
 */
internal val ArchiveIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Archive") {
        materialPath {
            moveTo(20.54f, 5.23f)
            lineToRelative(-1.39f, -1.68f)
            curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
            horizontalLineTo(6f)
            curveToRelative(-0.47f, 0f, -0.88f, 0.21f, -1.15f, 0.55f)
            lineTo(3.46f, 5.23f)
            curveTo(3.17f, 5.57f, 3f, 6.02f, 3f, 6.5f)
            verticalLineTo(19f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(6.5f)
            curveToRelative(0f, -0.48f, -0.17f, -0.93f, -0.46f, -1.27f)
            close()
            moveTo(12f, 17.5f)
            lineTo(6.5f, 12f)
            horizontalLineTo(10f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(3.5f)
            lineTo(12f, 17.5f)
            close()
            moveTo(5.12f, 5f)
            lineToRelative(0.81f, -1f)
            horizontalLineToRelative(12f)
            lineToRelative(0.94f, 1f)
            horizontalLineTo(5.12f)
            close()
        }
    }
}
