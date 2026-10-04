package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.IconBtn
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.MenuRow
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.TextTabs
import dev.mohak.scrinium.ui.Type

/**
 * The center tab. Empty: recent searches and the vault's tags. Typing:
 * matching notes (local + server full-text) and matching tasks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, tasksVm: TasksViewModel) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.refreshTags()
        if (query.isBlank()) focus.requestFocus()
    }

    val terms = remember(query) { query.split(' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() } }
    val taskHits = remember(tasks, terms) {
        if (terms.isEmpty()) emptyList()
        else tasks.filter { t -> terms.all { t.title.contains(it, true) || t.detail.contains(it, true) } }
    }

    Column(Modifier.fillMaxSize()) {
        InputBox(
            query,
            { vm.setSearchQuery(it) },
            "Search notes and tasks",
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            icon = R.drawable.ms_search,
            focusRequester = focus,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailing = if (query.isNotEmpty()) ({ IconBtn(R.drawable.ms_close, "Clear", { vm.setSearchQuery("") }, tint = Sc.text3) }) else null
        )
        if (query.isBlank()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (recent.isNotEmpty()) {
                    item { GroupHeader("Recent") }
                    items(recent, key = { "r-$it" }) { q -> MenuRow(R.drawable.ms_history, q, { vm.setSearchQuery(q) }) }
                }
                if (tags.isNotEmpty()) {
                    item { GroupHeader("Tags") }
                    item {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            tags.forEach { t ->
                                Text(
                                    buildAnnotatedString {
                                        append("#${t.tag}")
                                        pushStyle(SpanStyle(color = Sc.text3))
                                        append(" ${t.count}")
                                        pop()
                                    },
                                    style = Type.body.copy(color = Sc.accent),
                                    modifier = Modifier.clickable { vm.setSearchQuery("#${t.tag}") }.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                if (recent.isEmpty() && tags.isEmpty()) {
                    item { Text("Search note titles, text and tasks.", style = Type.meta, modifier = Modifier.padding(16.dp)) }
                }
            }
        } else {
            TextTabs(listOf("Notes ${results.size}", "Tasks ${taskHits.size}"), tab) { tab = it }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (tab == 0) {
                    if (results.isEmpty()) item { Text("No notes match \"$query\"", style = Type.meta, modifier = Modifier.padding(16.dp)) }
                    items(results, key = { it.path }) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.rememberSearch(query)
                                    vm.openSearchHit(hit)
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(highlight(hit.title, terms), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hit.path.substringBeforeLast('/', "").ifBlank { "/" }, style = Type.meta, maxLines = 1)
                            hit.snippet?.takeIf { it.isNotBlank() }?.let {
                                Text(highlight(it, terms), style = Type.meta.copy(color = Sc.text2, fontSize = Type.body.fontSize * 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    if (taskHits.isEmpty()) item { Text("No tasks match \"$query\"", style = Type.meta, modifier = Modifier.padding(16.dp)) }
                    items(taskHits, key = { it.id }) { t ->
                        TaskRow(t, null, onToggle = { tasksVm.toggleDone(t) }, onOpen = {
                            vm.rememberSearch(query)
                            tasksVm.openTask(t.id)
                        }, showStatus = true)
                    }
                }
            }
        }
    }
}

// Marks every case-insensitive occurrence of [terms] in [text].
private fun highlight(text: String, terms: List<String>): AnnotatedString = buildAnnotatedString {
    append(text)
    for (term in terms) {
        var i = text.indexOf(term, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = Sc.fill.copy(alpha = 0.6f), color = Sc.text), i, i + term.length)
            i = text.indexOf(term, i + term.length, ignoreCase = true)
        }
    }
}
