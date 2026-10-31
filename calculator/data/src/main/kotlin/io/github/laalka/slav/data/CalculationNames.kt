package io.github.laalka.slav.data

// The names the program gives to the calculations it makes from another one. A name is unique, so
// a copy and a revision cannot keep the name of their source: they take the smallest number, from
// 2 up, that is still free. The number of the source itself is not counted on: copying «Кронштейн (2)»
// is another «Кронштейн (N)», and a name that merely ends in a number («Деталь 2») has no copy number.

private val COPY_SUFFIX = Regex("""^(.+) \(\d+\)$""")
private val REVISION_SUFFIX = Regex("""^(.+) №\d+$""")

/** [name] without a trailing « (N)»: «Кронштейн (2)» is a copy of «Кронштейн», «Деталь 2» is not a copy of anything. */
internal fun copyBaseName(name: String): String = baseName(name, COPY_SUFFIX)

/** [name] without a trailing « №N»: «Кронштейн №3» is a revision of «Кронштейн». */
internal fun revisionBaseName(name: String): String = baseName(name, REVISION_SUFFIX)

/** Whether [name] ends in « №N», as the name of a revision does. */
internal fun hasRevisionNumber(name: String): Boolean = REVISION_SUFFIX.matches(name.trim())

/**
 * The name of a copy of the calculation called [sourceName]: «<base> (N)» with the smallest N ≥ 2 for
 * which [isTaken] is false. A source with a blank name, which is not a name and stays unchecked, is copied under it.
 */
internal fun nextCopyName(sourceName: String, isTaken: (String) -> Boolean): String =
    nextNumberedName(sourceName, copyBaseName(sourceName), { base, number -> "$base ($number)" }, isTaken)

/**
 * The name of a revision of the calculation called [sourceName]: «<base> №N» with the smallest N ≥ 2
 * for which [isTaken] is false. A source with a blank name is revised under it.
 */
internal fun nextRevisionName(sourceName: String, isTaken: (String) -> Boolean): String =
    nextNumberedName(sourceName, revisionBaseName(sourceName), { base, number -> "$base №$number" }, isTaken)

private fun baseName(name: String, suffix: Regex): String {
    val trimmed = name.trim()
    return suffix.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
}

private fun nextNumberedName(sourceName: String, base: String, named: (String, Int) -> String, isTaken: (String) -> Boolean): String {
    if (base.isEmpty()) return sourceName
    return generateSequence(2) { it + 1 }.map { named(base, it) }.first { !isTaken(it) }
}
