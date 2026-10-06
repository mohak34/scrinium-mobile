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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        sessionRepository = SessionRepository(appContext, tokenStore, api, reminders)
        notesRepository = NotesRepository(database.noteDao(), api)
        imageLoader = ImageLoader(api)
        syncEngine = SyncEngine(database.noteDao(), api) { tokenStore.token.value }
    }
}