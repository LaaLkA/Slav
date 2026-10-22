package io.github.laalka.slav.app.export

import io.github.laalka.slav.app.calculations.Breakdown
import java.nio.file.Path

/** The two exports of spec 8.5. [extension] includes its dot. */
enum class ExportFormat(val title: String, val dialogTitle: String, val extension: String) {
    PDF("PDF для клиента", "Экспорт расчёта в PDF", ".pdf"),
    XLSX("Excel с разбивкой", "Экспорт расчёта в Excel", ".xlsx"),
    ;

    /** Writes [breakdown] to [target], replacing the file; amounts are labelled with [currency]. */
    fun write(breakdown: Breakdown, currency: String, target: Path) = when (this) {
        PDF -> writeClientPdf(breakdown, currency, target)
        XLSX -> writeBreakdownXlsx(breakdown, currency, target)
    }
}

// Refused in file names by Windows (and ':' by the macOS Finder); control characters too.
private val unsafeInFileName = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/** The calculation name made safe as a file name, with the extension of [format]. */
fun exportFileName(name: String, format: ExportFormat): String {
    // Windows also refuses a name ending in a dot or a space.
    val base = name.replace(unsafeInFileName, "_").trim().trimEnd('.').ifEmpty { "Расчёт" }
    return base + format.extension
}
