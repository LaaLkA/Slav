package io.github.laalka.slav.app.editor

/**
 * The fields that stop the calculation with the name field added, when [name] is one another
 * calculation already has (spec: names are unique). A blank name is already flagged as missing, and
 * [taken] is not asked about it.
 */
internal fun Map<String, String>.withNameTaken(tag: String, name: String, taken: (String) -> Boolean): Map<String, String> =
    if (tag in this || name.isBlank() || !taken(name)) this else this + (tag to NAME_TAKEN)
