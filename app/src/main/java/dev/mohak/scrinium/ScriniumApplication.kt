package dev.mohak.scrinium

import android.app.Application
import androidx.work.WorkManager
import dev.mohak.scrinium.di.AppContainer

class ScriniumApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Foreground-only sync: cancel the periodic worker older installs
        // enqueued so nothing ever runs while the app is closed.
        WorkManager.getInstance(this).cancelUniqueWork("scrinium-sync-periodic")
    }
}
