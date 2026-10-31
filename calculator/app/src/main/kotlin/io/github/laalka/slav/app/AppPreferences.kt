package io.github.laalka.slav.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
    ;

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Choices that belong to this computer rather than to the settings: the settings travel in
 * exports and price snapshots (spec 8.4, 9), and a colour theme has no place there.
 */
class AppPreferences(private val file: Path) {
    private val properties = Properties().apply {
        if (file.exists()) file.inputStream().use(::load)
    }

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == properties.getProperty(THEME) } ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _developerMode = MutableStateFlow(properties.getProperty(DEVELOPER_MODE).toBoolean())

    /** Developer mode shows the buttons that fill an editor with random data; it is off until switched on here. */
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        save(THEME, mode.name)
        _themeMode.value = mode
    }

    fun setDeveloperMode(on: Boolean) {
        save(DEVELOPER_MODE, on.toString())
        _developerMode.value = on
    }

    private fun save(key: String, value: String) {
        properties.setProperty(key, value)
        file.parent.createDirectories()
        file.outputStream().use { properties.store(it, null) }
    }

    private companion object {
        const val THEME = "theme"
        const val DEVELOPER_MODE = "developer-mode"
    }
}
