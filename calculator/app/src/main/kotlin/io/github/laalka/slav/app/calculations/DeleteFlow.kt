package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.data.AdminPassword
import java.util.UUID

const val WRONG_PASSWORD = "Неверный пароль"

/** The question in front of a delete: which calculation or drawing, and what is wrong with the password typed so far. */
data class DeleteQuestion(val name: String, val error: String?)

/**
 * Deleting any calculation, whatever its status, needs the administrator password, and so does
 * removing a drawing from a calculated one. A wrong password deletes nothing and leaves the
 * question open with an error under the field; [delete] is called with the id asked about once
 * the password is right.
 */
class DeleteFlow(private val adminPassword: AdminPassword, private val delete: (UUID) -> Unit) {
    /** The id waiting for the password, null while nothing is being deleted. */
    var pending: UUID? = null
        private set

    var error: String? = null
        private set

    private var password = ""

    fun request(id: UUID) {
        pending = id
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

    /** True when the password was right and the id is deleted. */
    fun confirm(): Boolean {
        val id = pending ?: return false
        if (!adminPassword.verify(password.toCharArray())) {
            error = WRONG_PASSWORD
            return false
        }
        delete(id)
        cancel()
        return true
    }
}
