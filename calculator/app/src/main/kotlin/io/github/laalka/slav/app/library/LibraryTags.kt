package io.github.laalka.slav.app.library

/** Test tags of the library screen and of the editor's library dialogs. */
object LibraryTags {
    const val SEARCH = "library/search"
    const val EMPTY = "library/empty"
    const val NO_MATCHES = "library/no-matches"
    const val RENAME_FIELD = "library/rename/field"
    const val RENAME_CONFIRM = "library/rename/confirm"
    const val RENAME_CANCEL = "library/rename/cancel"
    const val DELETE_CONFIRM = "library/delete/confirm"
    const val DELETE_CANCEL = "library/delete/cancel"

    /** A template of the screen by its position; its parts are "<row>/name", "<row>/summary", "<row>/rename" and "<row>/delete". */
    fun row(index: Int) = "library/row/$index"

    /** «Добавить из библиотеки» above the parts of the editor. */
    const val ADD_FROM_LIBRARY = "editor/add-from-library"
    const val NOTICE = "editor/library/notice"
    const val SAVE_NAME = "editor/library/save/name"
    const val SAVE_CONFIRM = "editor/library/save/confirm"
    const val SAVE_CANCEL = "editor/library/save/cancel"
    const val REPLACE_QUESTION = "editor/library/replace/question"
    const val REPLACE_CONFIRM = "editor/library/replace/confirm"
    const val REPLACE_BACK = "editor/library/replace/back"
    const val PICK_SEARCH = "editor/library/pick/search"
    const val PICK_EMPTY = "editor/library/pick/empty"
    const val PICK_NO_MATCHES = "editor/library/pick/no-matches"
    const val PICK_CLOSE = "editor/library/pick/close"

    /** «В библиотеку» on the card of the part tagged [partTag]. */
    fun toLibrary(partTag: String) = "$partTag/to-library"

    /** A template of the picker by its position; its name is "<row>/name", the whole row adds it. */
    fun pickRow(index: Int) = "editor/library/pick/row/$index"
}
