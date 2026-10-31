package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.data.AdminPassword

const val WRONG_PASSWORD = "Неверный пароль"

/** The question in front of a delete: which calculation or drawing, and what is wrong with the password typed so far. */
data class DeleteQuestion(val name: String, val error: String?)

/** The question in front of an edit in place: which calculation, and what is wrong with the password typed so far. */
data class EditQuestion(val name: String, val error: String?)

/**
 * A step on a calculation that needs the administrator password: deleting it, removing a drawing
 * from a calculated one, editing a calculated record in place, sending it to an archive file. A
 * wrong password does nothing and leaves the question open with an error under the field;
 * [onVerified] is called with what was asked about, an id or a request, once the password is right.
 */
class PasswordFlow<T : Any>(private val adminPassword: AdminPassword, private val onVerified: (T) -> Unit) {
    /** What is waiting for the password, null while nothing is being asked. */
    var pending: T? = null
        private set

    var error: String? = null
        private set

    private var password = ""

    fun request(subject: T) {
        pending = subject
        password = ""
        error = null
    }

    fun onPasswordChange(text: String) {
        password = text
        error = null
    }

    fun cancel() {
        pending = null
        password = ""
        error = null
    }

    /** True when the password was right and [onVerified] has been called with what was asked about. */
    fun confirm(): Boolean {
        val subject = pending ?: return false
        if (!adminPassword.verify(password.toCharArray())) {
            error = WRONG_PASSWORD
            return false
        }
        onVerified(subject)
        cancel()
        return true
    }
}
