package dev.mohak.scrinium.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mohak.scrinium.data.remote.ScriniumApi
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request

// Where an image reference in a note points.
sealed interface ImageSource {
    /** Vault-relative path, served by the auth-gated asset API. */
    data class Vault(val path: String) : ImageSource
    /** Absolute http(s) URL on some other host. */
    data class External(val url: String) : ImageSource
}

/**
 * Resolves `![](url)` like the web's resolveAssetUrl: absolute URLs pass
 * through, anything else is relative to the note's folder. The destination
 * may sit in `<...>` and be percent-encoded; a `?query` or `#fragment` is
 * dropped. `/api/assets/...` (what the web resolves to) maps back to the
 * vault path.
 */
fun resolveImage(raw: String, notePath: String): ImageSource? {
    var clean = raw.trim().let { if (it.startsWith('<') && it.endsWith('>')) it.substring(1, it.length - 1).trim() else it }
    if (clean.isEmpty()) return null
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return ImageSource.External(clean)
    if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return null
    clean = clean.substringBefore('?').substringBefore('#')
    val parts = ArrayDeque<String>()
    when {
        clean.startsWith("/api/assets/") -> clean = clean.removePrefix("/api/assets/")
        clean.startsWith("/") -> Unit
        // The note's folder is a real path; only the destination is decoded.
        else -> notePath.split('/').dropLast(1).filter { it.isNotEmpty() }.forEach(parts::addLast)
    }
    for (seg in clean.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(decodeSegment(seg))
        }
    }
    return if (parts.isEmpty()) null else ImageSource.Vault(parts.joinToString("/"))
}

// decodeURIComponent: `+` stays a plus, a malformed escape keeps the raw text.
private fun decodeSegment(seg: String): String =
    if ('%' !in seg) seg
    else try {
        URLDecoder.decode(seg.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        seg
    }

/** Path from the note's folder to a vault path, as the web inserts it. */
fun noteRelative(notePath: String, vaultRel: String): String {
    val from = notePath.split('/').dropLast(1).filter { it.isNotEmpty() }
    val to = vaultRel.split('/').filter { it.isNotEmpty() }
    var i = 0
    while (i < from.size && i < to.size && from[i] == to[i]) i++
    return (List(from.size - i) { ".." } + to.drop(i)).joinToString("/").ifEmpty { vaultRel }
}

/** Files that render as images, matching the web's isImagePath. */
fun isImagePath(path: String) = Regex("""\.(png|jpe?g|gif|webp|avif)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)

/** Where an upload from [notePath] goes: its project's `attachments/`, else the vault's. */
fun attachmentFolder(notePath: String, projects: List<String>): String =
    projects.filter { notePath.startsWith("$it/") }.maxByOrNull { it.length }?.let { "$it/attachments" } ?: "attachments"

/** What to put in a note for an uploaded file: an embed for images, a link otherwise. */
fun attachmentMarkdown(notePath: String, vaultRel: String): String {
    val rel = noteRelative(notePath, vaultRel)
    val file = vaultRel.substringAfterLast('/')
    // A space or parenthesis would end a bare destination early.
    val dest = if (Regex("""[\s()<>]""").containsMatchIn(rel)) "<$rel>" else rel
    return if (isImagePath(vaultRel)) "![${file.substringBeforeLast('.')}]($dest)" else "[$file]($dest)"
}

/**
 * Loads note images into memory-bounded bitmaps. Vault images go through the
 * authed (disk-cached) asset client; external ones through a plain client so
 * the API token never leaves for another host.
 */
class ImageLoader(private val api: ScriniumApi) {
    private val external = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One fetch per image at a time: the preview asks again on every
    // recomposition that rebuilds the block.
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(source: ImageSource): ImageBitmap? {
        val key = source.toString()
        cache.get(key)?.let { return it }
        val job = inFlight.computeIfAbsent(key) { k ->
            scope.async(start = CoroutineStart.LAZY) { fetch(source, k) }
                .also { d -> d.invokeOnCompletion { inFlight.remove(k, d) } }
        }
        job.start()
        return job.await()
    }

    private suspend fun fetch(source: ImageSource, key: String): ImageBitmap? {
        val bytes = when (source) {
            is ImageSource.Vault -> api.fetchAsset(source.path)
            is ImageSource.External -> external.newCall(Request.Builder().url(source.url).build()).execute()
                .use { res -> if (res.isSuccessful) res.body.bytes() else null }
        } ?: return null
        return decodeSampled(bytes, MAX_DECODE_PX)?.asImageBitmap()?.also { cache.put(key, it) }
    }

    companion object {
        private const val MAX_DECODE_PX = 2048
        private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
        private val UPLOADABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /**
         * Browsers render png/jpeg/gif/webp; anything else (HEIC from the
         * camera) and photos over 10 MB are re-encoded as JPEG.
         * Returns bytes and their mime type, or null if it can't be decoded.
         */
        fun prepareUpload(bytes: ByteArray, mimeType: String?): Pair<ByteArray, String>? {
            if (mimeType in UPLOADABLE && bytes.size <= MAX_UPLOAD_BYTES) return bytes to mimeType!!
            val bitmap = decodeSampled(bytes, 2560) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return out.toByteArray() to "image/jpeg"
        }
    }
}
