package io.github.laalka.slav.app.editor

import java.util.UUID

/**
 * What the window asks of the editor that is open, whatever kind of calculation it edits: the full
 * [EditorModel] and the simplified one both save a draft as they are left and see the settings change.
 */
interface CalculationEditor {
    /** Writes the pending draft now. */
    fun flush()

    /** Saves what is pending before the editor is left; a revision draft nothing was changed in is dropped (spec 8.3). */
    fun leave()

    /** Back from the settings: a calculation on current prices sees what was changed there. */
    fun resume()

    /**
     * The calculation edited in place: it stays as it was until «Рассчитать», which writes it over
     * itself and opens its card. Null for a draft, which is edited and saved freely.
     */
    val inPlaceId: UUID?

    /** An edit in place has changes that leaving would lose, so the window asks before it leaves. */
    val discardsOnLeave: Boolean
}
