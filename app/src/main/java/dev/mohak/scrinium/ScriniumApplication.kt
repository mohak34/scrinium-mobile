package dev.mohak.scrinium

import android.app.Application
import dev.mohak.scrinium.di.AppContainer
import dev.mohak.scrinium.sync.SyncScheduler

class ScriniumApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}