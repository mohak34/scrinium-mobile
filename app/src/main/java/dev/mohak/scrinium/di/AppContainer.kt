package dev.mohak.scrinium.di

import android.content.Context
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.data.ImageLoader
import dev.mohak.scrinium.data.NotesRepository
import dev.mohak.scrinium.data.Prefs
import dev.mohak.scrinium.data.SecureTokenStore
import dev.mohak.scrinium.data.SessionRepository
import dev.mohak.scrinium.data.local.AppDatabase
import dev.mohak.scrinium.data.remote.ScriniumApi
import dev.mohak.scrinium.reminders.Reminders
import dev.mohak.scrinium.sync.SyncEngine
import dev.mohak.scrinium.update.Updater
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.widget.NotesWidget
import dev.mohak.scrinium.widget.TasksWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = AppDatabase.build(appContext)
    val tokenStore = SecureTokenStore(appContext)
    val prefs = Prefs(appContext)
    val reminders = Reminders(appContext)
    val updater = Updater(appContext)

    lateinit var sessionRepository: SessionRepository
    lateinit var api: ScriniumApi

    // The last full task list TasksViewModel loaded, saved for the tasks
    // widget. Null after sign-out.
    val loadedTasks = MutableStateFlow<List<TaskDto>?>(null)

    val notesRepository: NotesRepository
    val imageLoader: ImageLoader
    val syncEngine: SyncEngine

    init {
        api = ScriniumApi(
            baseUrl = BuildConfig.SCRINIUM_API_URL,
            tokenProvider = { tokenStore.token.value },
            onUnauthorized = {
                scope.launch { sessionRepository.forceSignOut() }
            },
            cacheDir = appContext.cacheDir
        )
        // Sign-out clears the widget now (the list may already be null, which
        // emits nothing) and resets the feed so a save still waiting is dropped.
        sessionRepository = SessionRepository(appContext, tokenStore, api, reminders) {
            loadedTasks.value = null
            withContext(Dispatchers.IO) { TasksWidget.clear(appContext) }
        }
        notesRepository = NotesRepository(database.noteDao(), api)
        imageLoader = ImageLoader(api)
        syncEngine = SyncEngine(database.noteDao(), api) { tokenStore.token.value }
        // Keep the home screen widget in step with Room; no-op when none is placed.
        scope.launch {
            database.noteDao().observeAll().debounce(1000).collect { NotesWidget.render(appContext, it) }
        }
        // A sign-out's null replaces a save still waiting on the debounce and
        // clears again after one already running. The startup null is skipped
        // so a cold start keeps the last list; a list landing after sign-out is not kept.
        scope.launch(Dispatchers.IO) {
            loadedTasks.drop(1).debounce(1000).collect { tasks ->
                if (tasks == null || tokenStore.token.value == null) TasksWidget.clear(appContext)
                else TasksWidget.save(appContext, tasks)
            }
        }
    }
}