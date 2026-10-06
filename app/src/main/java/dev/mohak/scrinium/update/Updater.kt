package dev.mohak.scrinium.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.IntentCompat
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.ScriniumApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class Release(@SerialName("tag_name") val tagName: String, val assets: List<Asset> = emptyList())

@Serializable
private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

/**
 * In-app updates from this repo's GitHub releases (published by
 * `.github/workflows/release.yml`). Settings calls [check] when it opens and
 * [install] on tap; the APK streams straight into a PackageInstaller
 * session and Android shows its own install prompt.
 */
class Updater(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val apkUrl: String) : State
        data object Downloading : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Plain client: the API client would send the vault token to GitHub.
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check() {
        _state.value = State.Checking
        _state.value = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(LATEST_RELEASE).header("Accept", "application/vnd.github+json").build()
                http.newCall(request).execute().use { res ->
                    if (res.code == 404) return@withContext State.UpToDate // no release yet
                    if (!res.isSuccessful) error("GitHub answered ${res.code}")
                    val release = json.decodeFromString<Release>(res.body.string())
                    val version = release.tagName.removePrefix("v")
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    if (version == BuildConfig.VERSION_NAME || apk == null) State.UpToDate else State.Available(version, apk.url)
                }
            }
        } catch (e: Exception) {
            State.Failed("Update check failed: ${e.message}")
        }
    }

    suspend fun install(update: State.Available) {
        // Android asks once per app before it may install APKs.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.value = State.Downloading
        _state.value = try {
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                installer.openSession(id).use { session ->
                    try {
                        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { res ->
                            if (!res.isSuccessful) error("download answered ${res.code}")
                            session.openWrite("base.apk", 0, res.body.contentLength()).use { out ->
                                res.body.byteStream().copyTo(out)
                                session.fsync(out)
                            }
                        }
                        val result = PendingIntent.getBroadcast(
                            context,
                            id,
                            Intent(context, InstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        session.commit(result.intentSender)
                    } catch (e: Exception) {
                        session.abandon()
                        throw e
                    }
                }
            }
            // Stays tappable in case the install prompt is dismissed.
            update
        } catch (e: Exception) {
            State.Failed("Update failed: ${e.message}")
        }
    }

    internal fun failed(message: String) {
        _state.value = State.Failed(message)
    }

    private companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/mohak34/scrinium-mobile/releases/latest"
    }
}

/** PackageInstaller reports here: show Android's confirm screen, or surface the failure in Settings. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as ScriniumApplication).container.updater
                .failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)")
        }
    }
}
