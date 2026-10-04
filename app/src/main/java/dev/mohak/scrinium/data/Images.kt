package dev.mohak.scrinium.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mohak.scrinium.data.remote.ScriniumApi
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * through, anything else is relative to the note's folder. `/api/assets/...`
 * (what the web resolves to) maps back to the vault path.
 */
fun resolveImage(raw: String, notePath: String): ImageSource? {
    val clean = raw.trim().removeSurrounding("<", ">")
    if (clean.isEmpty()) return null
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return ImageSource.External(clean)
    if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return null
    val joined = when {
        clean.startsWith("/api/assets/") ->
            clean.removePrefix("/api/assets/").split('/').joinToString("/") { Uri.decode(it) }
        clean.startsWith("/") -> clean
        else -> notePath.substringBeforeLast('/', "").let { dir -> if (dir.isEmpty()) clean else "$dir/$clean" }
    }
    val parts = ArrayDeque<String>()
    for (seg in joined.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(seg)
        }
    }
    return if (parts.isEmpty()) null else ImageSource.Vault(parts.joinToString("/"))
}

/** Path from the note's folder to a vault path, as the web inserts it. */
fun noteRelative(notePath: String, vaultRel: String): String {
    val from = notePath.split('/').dropLast(1).filter { it.isNotEmpty() }
    val to = vaultRel.split('/').filter { it.isNotEmpty() }
    var i = 0
    while (i < from.size && i < to.size && from[i] == to[i]) i++
    return (List(from.size - i) { ".." } + to.drop(i)).joinToString("/").ifEmpty { vaultRel }
}

/**
 * Loads note images into memory-bounded bitmaps. Vault images go through the
 * authed (disk-cached) asset client; external ones through a plain client so
 * the API token never leaves for another host.
 */
class ImageLoader(private val api: ScriniumApi) {
    private val external = OkHttpClient()

    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(source: ImageSource): ImageBitmap? {
        val key = source.toString()
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = when (source) {
                is ImageSource.Vault -> api.fetchAsset(source.path)
                is ImageSource.External -> external.newCall(Request.Builder().url(source.url).build()).execute()
                    .use { res -> if (res.isSuccessful) res.body.bytes() else null }
            } ?: return@withContext null
            decodeSampled(bytes, MAX_DECODE_PX)?.asImageBitmap()?.also { cache.put(key, it) }
        }
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
         * The server takes png/jpeg/gif/webp up to 10 MB. Anything else
         * (HEIC from the camera, huge photos) is re-encoded as JPEG.
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
