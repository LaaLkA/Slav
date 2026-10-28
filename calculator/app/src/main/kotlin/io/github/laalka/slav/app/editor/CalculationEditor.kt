package io.github.laalka.slav.app.editor

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
}
