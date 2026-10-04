package dev.mohak.scrinium.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-only display preferences: pinned paths and the new-note template.
 * The web keeps both in localStorage, so neither syncs; the phone keeps its
 * own the same way. Plain SharedPreferences, read once at startup.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("scrinium_prefs", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(sp.getStringSet(KEY_PINNED, emptySet())!!.toSet())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _template = MutableStateFlow(sp.getString(KEY_TEMPLATE, "") ?: "")
    val template: StateFlow<String> = _template.asStateFlow()

    fun setPinned(paths: Set<String>) {
        _pinned.value = paths
        sp.edit().putStringSet(KEY_PINNED, paths).apply()
    }

    fun setTemplate(value: String) {
        _template.value = value
        sp.edit().putString(KEY_TEMPLATE, value).apply()
    }

    /** Body for a new note: the template if it has `{{title}}`, else `# title`. Same rule as the web. */
    fun newNoteBody(title: String): String {
        val t = _template.value
        return if ("{{title}}" in t) t.replace("{{title}}", title) else "# $title\n\n"
    }

    private companion object {
        const val KEY_PINNED = "pinned"
        const val KEY_TEMPLATE = "new_note_template"
    }
}
