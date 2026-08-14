package dev.mohak.scrinium.di

import android.content.Context
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.data.NotesRepository
import dev.mohak.scrinium.data.SecureTokenStore
import dev.mohak.scrinium.data.SessionRepository
import dev.mohak.scrinium.data.local.AppDatabase
import dev.mohak.scrinium.data.remote.ScriniumApi
import dev.mohak.scrinium.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = AppDatabase.build(appContext)
    val tokenStore = SecureTokenStore(appContext)

    lateinit var sessionRepository: SessionRepository
    lateinit var api: ScriniumApi

    val notesRepository: NotesRepository
    val syncEngine: SyncEngine

    init {
        api = ScriniumApi(
            baseUrl = BuildConfig.SCRINIUM_API_URL,
            tokenProvider = { tokenStore.token.value },
            onUnauthorized = {
                scope.launch { sessionRepository.forceSignOut() }
            }
        )
        sessionRepository = SessionRepository(appContext, tokenStore, api)
        notesRepository = NotesRepository(database.noteDao(), api)
        syncEngine = SyncEngine(database.noteDao(), api) { tokenStore.token.value }
    }
}