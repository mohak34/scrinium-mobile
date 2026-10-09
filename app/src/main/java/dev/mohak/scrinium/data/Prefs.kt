package dev.mohak.scrinium.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-only display preferences: pinned paths, collapsed folders, the
 * new-note template and recent searches.
 * The web keeps these in localStorage, so none sync; the phone keeps its
 * own the same way. Plain SharedPreferences, read once at startup.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("scrinium_prefs", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(sp.getStringSet(KEY_PINNED, emptySet())!!.toSet())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _collapsed = MutableStateFlow(sp.getStringSet(KEY_COLLAPSED, emptySet())!!.toSet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    private val _template = MutableStateFlow(sp.getString(KEY_TEMPLATE, "") ?: "")
    val template: StateFlow<String> = _template.asStateFlow()

    // Newline-joined: a query never contains one (the search box is single-line).
    private val _recent = MutableStateFlow(sp.getString(KEY_RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() })
    val recentSearches: StateFlow<List<String>> = _recent.asStateFlow()

    fun addRecentSearch(q: String) {
        val clean = q.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + _recent.value.filter { it != clean }).take(MAX_RECENT)
        _recent.value = next
        sp.edit().putString(KEY_RECENT, next.joinToString("\n")).apply()
    }

    fun setPinned(paths: Set<String>) {
        _pinned.value = paths
        sp.edit().putStringSet(KEY_PINNED, paths).apply()
    }

    fun setCollapsed(paths: Set<String>) {
        _collapsed.value = paths
        sp.edit().putStringSet(KEY_COLLAPSED, paths).apply()
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
        const val KEY_COLLAPSED = "collapsed_folders"
        const val KEY_TEMPLATE = "new_note_template"
        const val KEY_RECENT = "recent_searches"
        const val MAX_RECENT = 6
    }
}
