package io.github.laalka.slav.app.settings

import io.github.laalka.slav.core.PriceBook
import io.github.laalka.slav.data.ImportSummary
import io.github.laalka.slav.data.SettingsImportException
import io.github.laalka.slav.data.SettingsTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

enum class ImportMode(val title: String) {
    REPLACE("Полная замена"),
    MERGE("Слияние"),
}

data class SectionChangeRow(val title: String, val added: Int, val changed: Int, val removed: Int)

/** An import read and previewed, waiting for the user's confirmation (spec 9). */
data class PendingImport(
    val mode: ImportMode,
    val fileName: String,
    val fileInfo: String,
    /** Only the sections the import changes. */
    val changes: List<SectionChangeRow>,
    val total: SectionChangeRow,
)

data class TransferMessage(val text: String, val isError: Boolean)

data class TransferState(
    val pending: PendingImport? = null,
    val message: TransferMessage? = null,
    /** The copy of the previous settings written by the last full replace. */
    val backupPath: String? = null,
)

/** «Импорт / экспорт» (spec 9): nothing is applied before the user confirms the preview. */
class TransferModel(
    private val store: SettingsStore,
    private val transfer: SettingsTransfer,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private var incoming: PriceBook? = null

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    fun suggestedFileName(): String = "slav-settings-${LocalDate.now(clock)}.json"

    fun export(target: Path) {
        val message = try {
            transfer.export(target)
            TransferMessage("Настройки сохранены в файл $target", isError = false)
        } catch (e: IOException) {
            TransferMessage("Не удалось сохранить файл $target: ${e.message}", isError = true)
        }
        _state.value = TransferState(message = message)
    }

    /** Reads [source] and shows what [mode] would change; the settings stay as they are. */
    fun prepareImport(source: Path, mode: ImportMode) {
        incoming = null
        _state.value = try {
            val imported = transfer.read(source)
            val summary = when (mode) {
                ImportMode.REPLACE -> transfer.previewReplace(imported.settings)
                ImportMode.MERGE -> transfer.previewMerge(imported.settings)
            }
            incoming = imported.settings
            val exported = fileDateFormat.format(imported.exportedAt.atZone(clock.zone))
            TransferState(
                pending = PendingImport(
                    mode = mode,
                    fileName = source.name,
                    fileInfo = "Сохранён $exported программой версии ${imported.appVersion}",
                    changes = summary.rows(),
                    total = SectionChangeRow("Всего", summary.added, summary.changed, summary.removed),
                ),
            )
        } catch (e: SettingsImportException) {
            TransferState(message = TransferMessage(e.message.orEmpty(), isError = true))
        }
    }

    fun confirmImport() {
        val pending = checkNotNull(_state.value.pending) { "No import to confirm" }
        val settings = checkNotNull(incoming)
        incoming = null
        _state.value = when (pending.mode) {
            ImportMode.REPLACE -> try {
                val backup = transfer.replace(settings)
                TransferState(
                    message = TransferMessage("Настройки заменены файлом «${pending.fileName}».", isError = false),
                    backupPath = backup.toString(),
                )
            } catch (e: IOException) {
                // The backup is written before anything is replaced: a failure leaves the settings untouched.
                TransferState(message = TransferMessage("Не удалось сохранить резервную копию, настройки не изменены: ${e.message}", isError = true))
            }
            ImportMode.MERGE -> {
                transfer.merge(settings)
                TransferState(message = TransferMessage("Настройки объединены с файлом «${pending.fileName}».", isError = false))
            }
        }
        store.reload()
    }

    fun cancelImport() {
        incoming = null
        _state.value = TransferState()
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, backupPath = null)
    }

    private companion object {
        val fileDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        fun ImportSummary.rows() = sections
            .filterValues { !it.isEmpty }
            .map { (section, changes) -> SectionChangeRow(section.title, changes.added, changes.changed, changes.removed) }
    }
}
