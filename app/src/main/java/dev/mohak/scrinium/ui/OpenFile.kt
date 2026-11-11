package dev.mohak.scrinium.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a vault file that isn't a note (a PDF, an image, anything) in
 * whichever app handles its type. The bytes land in the cache and leave
 * through the FileProvider, readable only by the app that is handed them.
 */
suspend fun openVaultFile(context: Context, vm: MainViewModel, path: String) {
    val name = path.substringAfterLast('/')
    val file = try {
        val bytes = vm.fileBytes(path)
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "open").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }
        }
    } catch (e: Exception) {
        vm.reportError("Couldn't fetch $name: ${e.message}")
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, name))
    } catch (_: ActivityNotFoundException) {
        vm.reportError("No app on this phone opens $name")
    }
}
