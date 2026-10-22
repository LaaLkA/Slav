package io.github.laalka.slav.data

import io.github.laalka.slav.core.PriceBook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A settings file that can be imported; nothing is applied yet. */
data class ImportedSettings(
    val settings: PriceBook,
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Instant,
)

/** Why a settings file cannot be imported; the message is written for the user. */
sealed class SettingsImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Made by a newer version of the program (spec 9). */
    class NewerFormat(val fileVersion: Int, val supportedVersion: Int) : SettingsImportException(
        "Файл настроек сохранён более новой версией программы (версия формата $fileVersion, " +
            "эта программа читает до $supportedVersion). Обновите программу.",
    )

    class Unreadable(file: Path, cause: IOException) :
        SettingsImportException("Не удалось прочитать файл «${file.fileName}»: ${cause.message}", cause)

    class Invalid(reason: String, cause: Throwable? = null) :
        SettingsImportException("Файл не является файлом настроек Slav или повреждён: $reason", cause)
}

/**
 * Settings export and import (spec 9). An import is [read], previewed with [previewReplace] or
 * [previewMerge], and applied with [replace] or [merge] only after the user confirms.
 */
class SettingsTransfer(
    private val settings: SettingsRepository,
    private val paths: AppPaths,
    private val appVersion: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Writes the current settings to [target] with the format version, the program version and the export time. */
    fun export(target: Path) {
        target.writeText(encode(settings.load()))
    }

    /** @throws SettingsImportException when the file cannot be imported, with the reason for the user. */
    fun read(source: Path): ImportedSettings {
        val text = try {
            source.readText()
        } catch (e: IOException) {
            throw SettingsImportException.Unreadable(source, e)
        }
        // Windows editors may put a byte-order mark before the JSON.
        val root = try {
            exchangeJson.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw SettingsImportException.Invalid("это не JSON (${e.message})", e)
        } ?: throw SettingsImportException.Invalid("в файле нет объекта настроек")

        // The version is checked first: a newer file may differ in everything else.
        val version = root.primitive(FORMAT_VERSION)?.intOrNull
            ?: throw SettingsImportException.Invalid("нет версии формата")
        if (version > PriceBook.FORMAT_VERSION) throw SettingsImportException.NewerFormat(version, PriceBook.FORMAT_VERSION)
        if (version < 1) throw SettingsImportException.Invalid("неизвестная версия формата $version")

        val fileAppVersion = root.primitive(APP_VERSION)?.contentOrNull
            ?: throw SettingsImportException.Invalid("нет версии программы")
        val exportedText = root.primitive(EXPORTED_AT)?.contentOrNull
            ?: throw SettingsImportException.Invalid("нет даты экспорта")
        val exportedAt = try {
            Instant.parse(exportedText)
        } catch (e: DateTimeParseException) {
            throw SettingsImportException.Invalid("неверная дата экспорта", e)
        }
        // SerializationException, thrown for a missing field or a bad number, is an IllegalArgumentException.
        val book = try {
            exchangeJson.decodeFromJsonElement(PriceBook.serializer(), JsonObject(root - APP_VERSION - EXPORTED_AT))
        } catch (e: IllegalArgumentException) {
            throw SettingsImportException.Invalid("настройки не читаются (${e.message})", e)
        }
        return ImportedSettings(book, version, fileAppVersion, exportedAt)
    }

    /** What [replace] would do: everything not in [incoming] is removed. */
    fun previewReplace(incoming: PriceBook): ImportSummary = replaceSummary(settings.load(), incoming)

    /** What [merge] would do; a merge never removes anything. */
    fun previewMerge(incoming: PriceBook): ImportSummary = mergeSettings(settings.load(), incoming).summary

    /**
     * Replaces the settings with [incoming]. The current settings are first written to a backup
     * file in the export format, so it can be imported back; its path is returned.
     */
    fun replace(incoming: PriceBook): Path {
        val backup = paths.backupsDir.createDirectories().resolve("settings-${fileStamp(clock.instant())}.json")
        backup.writeText(encode(settings.load()))
        settings.save(incoming)
        return backup
    }

    /** Merges [incoming] into the settings by name (spec 9) and returns the stored result. */
    fun merge(incoming: PriceBook): PriceBook {
        val merged = mergeSettings(settings.load(), incoming).merged
        settings.save(merged)
        return merged
    }

    private fun encode(book: PriceBook): String {
        val fields = exchangeJson.encodeToJsonElement(PriceBook.serializer(), book).jsonObject
        val file = buildJsonObject {
            // The settings model is the current one whatever version it was stored with.
            put(FORMAT_VERSION, PriceBook.FORMAT_VERSION)
            put(APP_VERSION, appVersion)
            put(EXPORTED_AT, clock.instant().toString())
            for ((name, value) in fields) {
                if (name != FORMAT_VERSION) put(name, value)
            }
        }
        return exchangeJson.encodeToString(JsonObject.serializer(), file)
    }

    private companion object {
        const val FORMAT_VERSION = "formatVersion"
        const val APP_VERSION = "appVersion"
        const val EXPORTED_AT = "exportedAt"

        /** People open the file to read it, so it is indented. */
        val exchangeJson = Json { prettyPrint = true }

        fun JsonObject.primitive(name: String) = get(name) as? JsonPrimitive
    }
}
