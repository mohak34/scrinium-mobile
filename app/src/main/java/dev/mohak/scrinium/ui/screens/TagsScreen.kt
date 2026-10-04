package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.EmptyState
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type

/** Vault tags with counts; tapping one lists its notes underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(vm: MainViewModel) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val loading by vm.tagsLoading.collectAsStateWithLifecycle()
    val selected by vm.selectedTag.collectAsStateWithLifecycle()
    val hits by vm.taggedHits.collectAsStateWithLifecycle()
    val hitsLoading by vm.taggedLoading.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar("Tags", onBack = { vm.closeTags() })
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refreshTags() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (!loading && tags.isEmpty()) item { EmptyState(R.drawable.ms_sell, "No tags yet", "Add #tags to notes to group them.") }
                tags.forEach { tag ->
                    val open = tag.tag == selected
                    item(key = "t-${tag.tag}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(if (open) Sc.hover else Color.Transparent)
                                .clickable { vm.selectTag(if (open) null else tag.tag) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#", style = Type.body.copy(color = Sc.accent))
                            Text(tag.tag, style = Type.body, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${tag.count}", style = Type.mono)
                        }
                    }
                    if (open) {
                        if (hitsLoading && hits.isEmpty()) item { Text("Loading", style = Type.meta, modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 8.dp)) }
                        items(hits, key = { "h-${tag.tag}-${it.path}" }) { hit ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.openTaggedHit(hit) }
                                    .padding(start = 34.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Sym(R.drawable.ms_description, tint = Sc.text3, size = 17.dp)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(hit.title, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val dir = hit.path.substringBeforeLast('/', "")
                                    if (dir.isNotBlank()) Text(dir, style = Type.meta, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
