package io.github.laalka.slav.app.settings

import io.github.laalka.slav.app.ADMIN_PASSWORD
import io.github.laalka.slav.app.AppPreferences
import io.github.laalka.slav.app.Deps
import io.github.laalka.slav.app.calculations.WRONG_PASSWORD
import io.github.laalka.slav.app.testAdminSeed
import io.github.laalka.slav.data.AppPaths
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeveloperModeModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var deps: Deps
    private lateinit var general: GeneralModel

    private val preferencesFile get() = temp.root.toPath().resolve("data").resolve("preferences.properties")

    /** What the preferences file holds; empty while nothing was ever stored. */
    private fun storedPreferences(): String = preferencesFile.takeIf { it.exists() }?.readText().orEmpty()

    @BeforeTest
    fun open() {
        deps = Deps(AppPaths(temp.newFolder("data").toPath()), appVersion = "test", adminSeed = testAdminSeed())
        general = SettingsModel(deps.settings, deps.settingsTransfer, deps.preferences, deps.adminPassword).general
    }

    @AfterTest
    fun close() = deps.close()

    @Test
    fun `developer mode is off on a first start`() {
        assertFalse(general.state.value.developerMode)
        assertFalse(deps.preferences.developerMode.value)
        assertNull(general.state.value.developerQuestion)
    }

    @Test
    fun `switching it on asks the administrator password and stays off until the right one is given`() {
        general.onDeveloperModeChange(true)

        assertEquals(DeveloperQuestion(error = null), general.state.value.developerQuestion)
        assertFalse(general.state.value.developerMode, "off while the password is not given")
        assertFalse(deps.preferences.developerMode.value)
    }

    @Test
    fun `a wrong password keeps it off and leaves the question open with its error`() {
        general.onDeveloperModeChange(true)
        general.onDeveloperPasswordChange("not-the-password")

        general.confirmDeveloperMode()

        assertEquals(DeveloperQuestion(error = WRONG_PASSWORD), general.state.value.developerQuestion)
        assertFalse(general.state.value.developerMode)
        assertFalse(deps.preferences.developerMode.value)
        assertFalse("developer-mode=true" in storedPreferences(), "nothing stored")

        // Typing again clears the error, and a second wrong try shows it again.
        general.onDeveloperPasswordChange("still-wrong")
        assertEquals(DeveloperQuestion(error = null), general.state.value.developerQuestion)
        general.confirmDeveloperMode()
        assertEquals(DeveloperQuestion(error = WRONG_PASSWORD), general.state.value.developerQuestion)
    }

    @Test
    fun `cancelling the question leaves it off, and an empty password is a wrong one`() {
        general.onDeveloperModeChange(true)
        general.confirmDeveloperMode()
        assertEquals(DeveloperQuestion(error = WRONG_PASSWORD), general.state.value.developerQuestion)

        general.cancelDeveloperMode()

        assertNull(general.state.value.developerQuestion)
        assertFalse(general.state.value.developerMode)
    }

    @Test
    fun `the right password switches it on, closes the question and survives a restart`() {
        general.onDeveloperModeChange(true)
        general.onDeveloperPasswordChange(ADMIN_PASSWORD)

        general.confirmDeveloperMode()

        assertNull(general.state.value.developerQuestion)
        assertTrue(general.state.value.developerMode)
        assertTrue(deps.preferences.developerMode.value)
        assertTrue(AppPreferences(preferencesFile).developerMode.value, "read back from the file")
    }

    @Test
    fun `switching it off asks no password and survives a restart`() {
        general.onDeveloperModeChange(true)
        general.onDeveloperPasswordChange(ADMIN_PASSWORD)
        general.confirmDeveloperMode()

        general.onDeveloperModeChange(false)

        assertNull(general.state.value.developerQuestion, "off is not asked about")
        assertFalse(general.state.value.developerMode)
        assertFalse(AppPreferences(preferencesFile).developerMode.value)
    }

    @Test
    fun `it belongs to this computer and is neither a setting nor in the price book`() {
        val before = deps.settings.load()

        general.onDeveloperModeChange(true)
        general.onDeveloperPasswordChange(ADMIN_PASSWORD)
        general.confirmDeveloperMode()

        assertEquals(before, deps.settings.load())
        assertTrue("developer-mode=true" in storedPreferences(), "kept with the preferences")
    }
}
