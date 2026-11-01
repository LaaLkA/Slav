package io.github.laalka.slav.app.ui

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons of the screens, drawn as in the mockups (Lucide's outlines on a 24-unit grid, a 2-unit line): the rail has
 * its own in [RailIcons]. They carry no colour of their own: an `Icon` tints them with the text beside them.
 */
internal object LineIcons {
    val Back: ImageVector by lazy { lineIcon("Back", "M19 12H5M12 19l-7-7 7-7") }

    val Plus: ImageVector by lazy { lineIcon("Plus", "M12 5v14M5 12h14") }

    val Search: ImageVector by lazy { lineIcon("Search", "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0zM21 21l-4.3-4.3") }

    val Calendar: ImageVector by lazy {
        lineIcon("Calendar", "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM16 3v4M8 3v4M3 11h18")
    }

    val Trash: ImageVector by lazy { lineIcon("Trash", "M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6") }

    val Pencil: ImageVector by lazy { lineIcon("Pencil", "M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z") }

    val Copy: ImageVector by lazy { lineIcon("Copy", "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM5 15V5a2 2 0 0 1 2-2h8") }

    val Refresh: ImageVector by lazy { lineIcon("Refresh", "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5") }

    val Reset: ImageVector by lazy { lineIcon("Reset", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5") }

    val Archive: ImageVector by lazy { lineIcon("Archive", "M4 4h16a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM5 9v10h14V9M10 13h4") }

    val Dice: ImageVector by lazy {
        lineIcon("Dice", "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM8.5 8.5h.01M15.5 15.5h.01M12 12h.01")
    }

    val ChevronDown: ImageVector by lazy { lineIcon("ChevronDown", "M6 9l6 6 6-6") }
}
