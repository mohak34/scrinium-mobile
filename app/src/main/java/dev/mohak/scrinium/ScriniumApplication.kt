package dev.mohak.scrinium

import android.app.Application
import androidx.work.WorkManager
import dev.mohak.scrinium.di.AppContainer
import dev.mohak.scrinium.reminders.Reminders

class ScriniumApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Reminders.createChannel(this)
        // Foreground-only sync: cancel the periodic worker older installs
        // enqueued so nothing ever runs while the app is closed.
        WorkManager.getInstance(this).cancelUniqueWork("scrinium-sync-periodic")
    }
}
