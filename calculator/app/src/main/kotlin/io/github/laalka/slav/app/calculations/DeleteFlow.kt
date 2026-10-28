package io.github.laalka.slav.app.calculations

import io.github.laalka.slav.data.AdminPassword
import io.github.laalka.slav.data.CalculationRepository
import java.util.UUID

const val WRONG_PASSWORD = "Неверный пароль"

/** The question in front of a delete: which calculation, and what is wrong with the password typed so far. */
data class DeleteQuestion(val name: String, val error: String?)

/**
 * Deleting any calculation, whatever its status, needs the administrator password. A wrong
 * password deletes nothing and leaves the question open with an error under the field.
 */
class DeleteFlow(private val calculations: CalculationRepository, private val adminPassword: AdminPassword) {
    /** The calculation waiting for the password, null while nothing is being deleted. */
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

    /** True when the password was right and the calculation is deleted. */
    fun confirm(): Boolean {
        val id = pending ?: return false
        if (!adminPassword.verify(password.toCharArray())) {
            error = WRONG_PASSWORD
            return false
        }
        calculations.delete(id)
        cancel()
        return true
    }
}
