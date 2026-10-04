package dev.mohak.scrinium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Screen
import dev.mohak.scrinium.ui.ScriniumTheme
import dev.mohak.scrinium.ui.BottomTabs
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.TabScreens
import dev.mohak.scrinium.ui.screens.BoardScreen
import dev.mohak.scrinium.ui.screens.CalendarScreen
import dev.mohak.scrinium.ui.TasksViewModel
import dev.mohak.scrinium.ui.screens.EditorScreen
import dev.mohak.scrinium.ui.screens.LoginScreen
import dev.mohak.scrinium.ui.screens.NotesScreen
import dev.mohak.scrinium.ui.screens.SearchScreen
import dev.mohak.scrinium.ui.screens.SettingsScreen
import dev.mohak.scrinium.ui.screens.TaskDetailScreen
import dev.mohak.scrinium.ui.screens.TasksScreen
import dev.mohak.scrinium.ui.screens.TagsScreen
import dev.mohak.scrinium.ui.screens.TrashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        // Set by a reminder notification: open that task on launch.
        const val EXTRA_TASK_ID = "taskId"
    }

    private val container by lazy { (application as ScriniumApplication).container }
    private val vm: MainViewModel by viewModels { MainViewModel.factory(container) }
    private val tasksVm: TasksViewModel by viewModels { TasksViewModel.factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark, so light bar icons whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        vm.registerSignInAction { container.sessionRepository.signIn(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                vm.syncOnForeground()
                if (vm.signedIn.value) tasksVm.refresh(quiet = true)
            }
        })
        if (savedInstanceState == null) openTaskFrom(intent)
        // Emulator testing without Google: `am start --es dev_token <raw token>`.
        if (BuildConfig.DEBUG) intent?.getStringExtra("dev_token")?.let { token ->
            lifecycleScope.launch { container.sessionRepository.useDevToken(token, intent.getStringExtra("dev_email") ?: "dev") }
        }
        setContent {
            ScriniumTheme {
                val signedIn by vm.signedIn.collectAsStateWithLifecycle()
                if (signedIn) {
                    AppContent(vm, tasksVm)
                } else {
                    LoginScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTaskFrom(intent)
    }

    private fun openTaskFrom(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_TASK_ID) ?: return
        intent.removeExtra(EXTRA_TASK_ID)
        if (vm.signedIn.value) tasksVm.openTask(id)
    }
}

@Composable
private fun AppContent(vm: MainViewModel, tasksVm: TasksViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val openTaskId by tasksVm.openTaskId.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Sc.bg).systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            // The task detail sits on top of whichever screen opened it.
            val taskId = openTaskId
            if (taskId != null) {
                BackHandler { tasksVm.closeTask() }
                TaskDetailScreen(vm, tasksVm, taskId)
            } else {
                AppScreen(vm, tasksVm, screen)
            }
        }
        if (openTaskId == null && screen in TabScreens) {
            BottomTabs(screen) { vm.openTab(it) }
        }
    }
}

@Composable
private fun AppScreen(vm: MainViewModel, tasksVm: TasksViewModel, screen: Screen) {
    BackHandler(enabled = screen != Screen.Notes) {
        when (screen) {
            is Screen.Editor -> vm.closeEditor()
            Screen.Search -> vm.closeSearch()
            Screen.Settings -> vm.closeSettings()
            Screen.Tags -> vm.closeTags()
            Screen.Trash -> vm.closeTrash()
            Screen.Tasks, Screen.Board, Screen.Calendar -> vm.openTab(Screen.Notes)
            Screen.Notes -> Unit
        }
    }
    when (screen) {
        Screen.Notes -> NotesScreen(vm)
        is Screen.Editor -> EditorScreen(vm, tasksVm)
        Screen.Search -> SearchScreen(vm, tasksVm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Tags -> TagsScreen(vm)
        Screen.Trash -> TrashScreen(vm)
        Screen.Tasks -> TasksScreen(tasksVm)
        Screen.Board -> BoardScreen(tasksVm)
        Screen.Calendar -> CalendarScreen(tasksVm)
    }
}
