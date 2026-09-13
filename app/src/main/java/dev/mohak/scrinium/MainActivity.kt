package dev.mohak.scrinium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Screen
import dev.mohak.scrinium.ui.ScriniumTheme
import dev.mohak.scrinium.ui.screens.EditorScreen
import dev.mohak.scrinium.ui.screens.LoginScreen
import dev.mohak.scrinium.ui.screens.NotesScreen
import dev.mohak.scrinium.ui.screens.SearchScreen
import dev.mohak.scrinium.ui.screens.SettingsScreen
import dev.mohak.scrinium.ui.screens.TagsScreen
import dev.mohak.scrinium.ui.screens.TrashScreen

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as ScriniumApplication).container }
    private val vm: MainViewModel by viewModels { MainViewModel.factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.registerSignInAction { container.sessionRepository.signIn(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                vm.syncOnForeground()
            }
        })
        setContent {
            ScriniumTheme {
                val signedIn by vm.signedIn.collectAsStateWithLifecycle()
                if (signedIn) {
                    AppContent(vm)
                } else {
                    LoginScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun AppContent(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.Notes) {
        when (screen) {
            is Screen.Editor -> vm.closeEditor()
            Screen.Search -> vm.closeSearch()
            Screen.Settings -> vm.closeSettings()
            Screen.Tags -> vm.closeTags()
            Screen.Trash -> vm.closeTrash()
            else -> Unit
        }
    }
    when (val s = screen) {
        Screen.Notes -> NotesScreen(vm)
        is Screen.Editor -> EditorScreen(vm)
        Screen.Search -> SearchScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Tags -> TagsScreen(vm)
        Screen.Trash -> TrashScreen(vm)
    }
}