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

    fun setThemeMode(mode: ThemeMode) {
        properties.setProperty(THEME, mode.name)
        file.parent.createDirectories()
        file.outputStream().use { properties.store(it, null) }
        _themeMode.value = mode
    }

    private companion object {
        const val THEME = "theme"
    }
}
