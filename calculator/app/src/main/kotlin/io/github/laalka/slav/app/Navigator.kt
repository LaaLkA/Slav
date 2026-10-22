package io.github.laalka.slav.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.laalka.slav.app.calculations.CardModel
import io.github.laalka.slav.app.editor.EditorModel
import io.github.laalka.slav.app.settings.SettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Where the user can go. */
sealed interface Screen {
    data object Calculations : Screen

    /** [id] is null for «Новый расчёт». */
    data class Editor(val id: UUID?) : Screen

    data class Card(val id: UUID) : Screen

    data object Settings : Screen
}

/** What is open, with the state holder of the page. */
sealed interface Page {
    data object Calculations : Page

    data class Editor(val model: EditorModel) : Page

    data class Card(val model: CardModel) : Page

    data object Settings : Page
}

enum class LeaveAnswer { KEEP_DRAFT, DISCARD, STAY }

/**
 * The navigation of the window. «Расчёты» keeps its page while «Настройки» is open, so a price
 * filled in there is one click away from the calculation that needed it. Leaving the editor in
 * any other way, closing the window included, goes through [EditorModel.leave] and may first ask
 * whether to keep the edit as a draft (spec 8.3).
 */
class Navigator(private val deps: Deps, private val scope: CoroutineScope) {
    /** Created once: the settings tabs keep their state while the user visits other screens. */
    val settings = SettingsModel(deps.settings, deps.settingsTransfer, deps.preferences)

    private var calculationsPage: Page = Page.Calculations
    private val _page = MutableStateFlow<Page>(Page.Calculations)
    val page: StateFlow<Page> = _page.asStateFlow()

    private var afterLeave: (() -> Unit)? = null
    private val _leaveQuestion = MutableStateFlow<String?>(null)

    /** The name of the calculation the user is asked about, while the question is open. */
    val leaveQuestion: StateFlow<String?> = _leaveQuestion.asStateFlow()

    fun navigate(to: Screen) {
        when (to) {
            Screen.Settings -> {
                openEditor()?.flush()
                _page.value = Page.Settings
            }
            Screen.Calculations -> leaveThen { show(Page.Calculations) }
            is Screen.Editor -> leaveThen { show(Page.Editor(editor(to.id))) }
            is Screen.Card -> leaveThen { show(card(to.id)) }
        }
    }

    /** The rail's «Расчёты»: back to the page left for the settings, or to the start page from within the section. */
    fun selectCalculations() {
        if (_page.value != Page.Settings) {
            navigate(Screen.Calculations)
            return
        }
        when (val page = calculationsPage) {
            is Page.Editor -> page.model.resume()
            is Page.Card -> page.model.reload()
            Page.Calculations, Page.Settings -> Unit
        }
        _page.value = calculationsPage
    }

    /** Closes the window through [exit] once the open editor, if any, has been left. */
    fun requestExit(exit: () -> Unit) = leaveThen(exit)

    fun answerLeave(answer: LeaveAnswer) {
        val action = afterLeave ?: return
        afterLeave = null
        _leaveQuestion.value = null
        when (answer) {
            LeaveAnswer.KEEP_DRAFT -> action()
            LeaveAnswer.DISCARD -> {
                openEditor()?.discardChanges()
                action()
            }
            LeaveAnswer.STAY -> Unit
        }
    }

    private fun leaveThen(action: () -> Unit) {
        val editor = openEditor()
        if (editor == null || !editor.leave()) {
            action()
        } else {
            afterLeave = action
            _leaveQuestion.value = editor.name
        }
    }

    private fun openEditor(): EditorModel? = (calculationsPage as? Page.Editor)?.model

    private fun show(page: Page) {
        calculationsPage = page
        _page.value = page
    }

    private fun editor(id: UUID?) = EditorModel(
        calculations = deps.calculations,
        settings = deps.settings,
        scope = scope,
        id = id,
        // The editor has saved the calculation and has nothing left to ask.
        onCalculated = { saved -> show(card(saved)) },
    )

    private fun card(id: UUID) = Page.Card(CardModel(deps.calculations, deps.settings, id))
}

/** One navigator per [deps]; its autosaves run in the composition's scope, on the UI thread. */
@Composable
fun rememberNavigator(deps: Deps): Navigator {
    val scope = rememberCoroutineScope()
    return remember(deps) { Navigator(deps, scope) }
}
