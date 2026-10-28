package io.github.laalka.slav.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.laalka.slav.app.calculations.CalculationListModel
import io.github.laalka.slav.app.calculations.CardLink
import io.github.laalka.slav.app.calculations.CardModel
import io.github.laalka.slav.app.calculations.ListRow
import io.github.laalka.slav.app.editor.CalculationEditor
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.library.LibraryModel
import io.github.laalka.slav.app.quick.QuickEditorModel
import io.github.laalka.slav.app.settings.SettingsModel
import io.github.laalka.slav.data.CalculationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Where the user can go. */
sealed interface Screen {
    data object Calculations : Screen

    /**
     * [id] is null for «Новый расчёт», which is then a calculation of [kind]; a saved one is opened in
     * the editor of its own kind, whatever [kind] says.
     */
    data class Editor(val id: UUID?, val kind: CalculationKind = CalculationKind.FULL) : Screen

    data class Card(val id: UUID) : Screen

    data object Library : Screen

    data object Settings : Screen
}

/** What is open, with the state holder of the page. */
sealed interface Page {
    data object Calculations : Page

    data class Editor(val model: EditorModel) : Page

    /** The editor of a simplified calculation. */
    data class QuickEditor(val model: QuickEditorModel) : Page

    data class Card(val model: CardModel) : Page

    data object Library : Page

    data object Settings : Page
}

/**
 * The navigation of the window. «Расчёты» keeps its page while «Детали» or «Настройки» is open, so
 * a price filled in there is one click away from the calculation that needed it. Leaving the editor in
 * any other way, closing the window included, goes through [EditorModel.leave], which saves the
 * draft and drops a revision draft nothing was changed in (spec 8.3).
 */
class Navigator(private val deps: Deps, private val scope: CoroutineScope) {
    /** Created once: the settings tabs keep their state while the user visits other screens. */
    val settings = SettingsModel(deps.settings, deps.settingsTransfer, deps.preferences, deps.adminPassword)

    /** Created once as well: search, filters and sorting stay while calculations are opened. */
    val calculationList = CalculationListModel(deps.calculations, deps.settings, deps.adminPassword)

    /** Created once: the search of «Детали» stays while the user visits other screens. */
    val library = LibraryModel(deps.partTemplates)

    private var calculationsPage: Page = Page.Calculations
    private val _page = MutableStateFlow<Page>(Page.Calculations)
    val page: StateFlow<Page> = _page.asStateFlow()

    fun navigate(to: Screen) {
        when (to) {
            Screen.Settings -> {
                openEditor()?.flush()
                _page.value = Page.Settings
            }
            Screen.Library -> {
                openEditor()?.flush()
                library.reload()
                _page.value = Page.Library
            }
            Screen.Calculations -> leaveThen { show(Page.Calculations) }
            is Screen.Editor -> leaveThen { show(editorPage(to)) }
            is Screen.Card -> leaveThen { show(card(to.id)) }
        }
    }

    /** A row of the list: a priced calculation opens its card, a draft its editor, since a draft has no price (spec 7.2). */
    fun open(row: ListRow) = navigate(if (row.isDraft) Screen.Editor(row.id) else Screen.Card(row.id))

    /** A link of a card: the calculation it was made from, or a newer version of it; opened like a row of the list. */
    fun open(link: CardLink) = navigate(if (link.isDraft) Screen.Editor(link.id) else Screen.Card(link.id))

    /** Spec 8.3: the copy is a draft, opened in the editor, and is priced on the current settings. */
    fun copy(id: UUID) = navigate(Screen.Editor(calculationList.copy(id)))

    /** The rail's «Расчёты»: back to the page left for «Детали» or the settings, or to the start page from within the section. */
    fun selectCalculations() {
        if (_page.value != Page.Settings && _page.value != Page.Library) {
            navigate(Screen.Calculations)
            return
        }
        when (val page = calculationsPage) {
            is Page.Editor -> page.model.resume()
            is Page.QuickEditor -> page.model.resume()
            is Page.Card -> page.model.reload()
            Page.Calculations -> calculationList.reload()
            Page.Library, Page.Settings -> Unit
        }
        _page.value = calculationsPage
    }

    /** Closes the window through [exit] once the open editor, if any, has been left. */
    fun requestExit(exit: () -> Unit) = leaveThen(exit)

    private fun leaveThen(action: () -> Unit) {
        openEditor()?.leave()
        action()
    }

    private fun openEditor(): CalculationEditor? = when (val page = calculationsPage) {
        is Page.Editor -> page.model
        is Page.QuickEditor -> page.model
        else -> null
    }

    private fun show(page: Page) {
        if (page == Page.Calculations) calculationList.reload()
        calculationsPage = page
        _page.value = page
    }

    private fun editorPage(to: Screen.Editor): Page {
        val kind = to.id?.let { id -> requireNotNull(deps.calculations.get(id)) { "No calculation $id" }.kind } ?: to.kind
        return when (kind) {
            CalculationKind.FULL -> Page.Editor(editor(to.id))
            CalculationKind.QUICK -> Page.QuickEditor(quickEditor(to.id))
        }
    }

    private fun quickEditor(id: UUID?) = QuickEditorModel(
        calculations = deps.calculations,
        settings = deps.settings,
        attachmentRepository = deps.attachments,
        fileOpener = deps.fileOpener,
        scope = scope,
        id = id,
        // The editor has saved the calculation and has nothing left to ask.
        onCalculated = { saved -> show(card(saved)) },
    )

    private fun editor(id: UUID?) = EditorModel(
        calculations = deps.calculations,
        settings = deps.settings,
        attachmentRepository = deps.attachments,
        partTemplates = deps.partTemplates,
        fileOpener = deps.fileOpener,
        scope = scope,
        id = id,
        // The editor has saved the calculation and has nothing left to ask.
        onCalculated = { saved -> show(card(saved)) },
    )

    // A new version (recalculation, new run size) opens as its own card; a deleted calculation leaves nothing to show.
    private fun card(id: UUID): Page.Card = Page.Card(
        CardModel(
            deps.calculations,
            deps.settings,
            deps.adminPassword,
            deps.attachments,
            deps.fileOpener,
            id,
            onRevision = { revision -> show(card(revision)) },
            onDeleted = { show(Page.Calculations) },
        ),
    )
}

/** One navigator per [deps]; its autosaves run in the composition's scope, on the UI thread. */
@Composable
fun rememberNavigator(deps: Deps): Navigator {
    val scope = rememberCoroutineScope()
    return remember(deps) { Navigator(deps, scope) }
}
