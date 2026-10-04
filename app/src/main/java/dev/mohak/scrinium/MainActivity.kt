package dev.mohak.scrinium

import android.content.Intent
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
        enableEdgeToEdge()
        vm.registerSignInAction { container.sessionRepository.signIn(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                vm.syncOnForeground()
                if (vm.signedIn.value) tasksVm.refresh(quiet = true)
            }
        })
        if (savedInstanceState == null) openTaskFrom(intent)
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
    // The task detail sits on top of whichever screen opened it.
    openTaskId?.let { id ->
        BackHandler { tasksVm.closeTask() }
        TaskDetailScreen(vm, tasksVm, id)
        return
    }
    BackHandler(enabled = screen != Screen.Notes) {
        when (screen) {
            is Screen.Editor -> vm.closeEditor()
            Screen.Search -> vm.closeSearch()
            Screen.Settings -> vm.closeSettings()
            Screen.Tags -> vm.closeTags()
            Screen.Trash -> vm.closeTrash()
            Screen.Tasks -> vm.closeTasks()
            else -> Unit
        }
    }
    when (val s = screen) {
        Screen.Notes -> NotesScreen(vm)
        is Screen.Editor -> EditorScreen(vm, tasksVm)
        Screen.Search -> SearchScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Tags -> TagsScreen(vm)
        Screen.Trash -> TrashScreen(vm)
        Screen.Tasks -> TasksScreen(vm, tasksVm)
    }
}