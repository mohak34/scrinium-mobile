package dev.mohak.scrinium.data.remote

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class ManifestEntry(val path: String, val updatedAt: Double, val contentHash: String)

@Serializable
data class MobileAuthRequest(val googleIdToken: String)

@Serializable
data class MobileAuthResponse(val apiToken: String, val email: String)

@Serializable
data class RenameRequest(val newPath: String)

@Serializable
data class SearchResult(val path: String, val title: String, val snippet: String)

@Serializable
data class TagCount(val tag: String, val count: Int)

@Serializable
data class TaggedHit(val path: String, val title: String, val snippet: String)

@Serializable
data class TrashEntry(
    val trashName: String,
    val originalPath: String,
    val deletedAt: Long = 0L,
    val isDir: Boolean = false,
    val size: Long? = null
)

@Serializable
data class RestoreRequest(val trashName: String)

@Serializable
data class RestoreResponse(val ok: Boolean = true, val path: String = "")

@Serializable
data class PublicShare(
    val id: String,
    val notePath: String,
    val createdAt: Long = 0L,
    val hasPassword: Boolean = false
)

@Serializable
data class CreateShareRequest(val path: String, val password: String? = null)

private interface ScriniumService {
    @POST("api/auth/mobile")
    suspend fun mobileAuth(@Body body: MobileAuthRequest): MobileAuthResponse

    @GET("api/notes/manifest")
    suspend fun manifest(): List<ManifestEntry>

    @GET("api/notes/{path}")
    suspend fun note(@Path("path", encoded = true) path: String): ResponseBody

    @PUT("api/notes/{path}")
    suspend fun putNote(
        @Path("path", encoded = true) path: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @PATCH("api/notes/{path}")
    suspend fun rename(
        @Path("path", encoded = true) path: String,
        @Body body: RenameRequest
    ): Response<ResponseBody>

    @DELETE("api/notes/{path}")
    suspend fun delete(@Path("path", encoded = true) path: String): Response<ResponseBody>

    @GET("api/search")
    suspend fun search(@Query("q") q: String): List<SearchResult>

    @GET("api/tags")
    suspend fun tags(): List<TagCount>

    @GET("api/tagged")
    suspend fun tagged(@Query("tag") tag: String): List<TaggedHit>

    @GET("api/trash")
    suspend fun trash(): List<TrashEntry>

    @POST("api/trash/restore")
    suspend fun restoreTrash(@Body body: RestoreRequest): RestoreResponse

    @DELETE("api/trash")
    suspend fun purgeTrash(@Query("trashName") trashName: String): Response<ResponseBody>

    @DELETE("api/trash")
    suspend fun emptyTrash(@Query("all") all: Int): Response<ResponseBody>

    @GET("api/shares")
    suspend fun shares(@Query("path") path: String): List<PublicShare>

    @POST("api/shares")
    suspend fun createShare(@Body body: CreateShareRequest): PublicShare

    @DELETE("api/shares/{id}")
    suspend fun deleteShare(@Path("id") id: String): Response<ResponseBody>
}

class ApiException(val status: Int) : Exception("API error $status")

class ScriniumApi(
    baseUrl: String,
    tokenProvider: () -> String?,
    onUnauthorized: () -> Unit
) {
    private val textMediaType = "text/plain".toMediaType()

    private val service: ScriniumService = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenProvider, onUnauthorized))
                .build()
        )
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ScriniumService::class.java)

    suspend fun exchangeGoogleIdToken(idToken: String): MobileAuthResponse =
        service.mobileAuth(MobileAuthRequest(idToken))

    suspend fun fetchManifest(): List<ManifestEntry> = service.manifest()

    suspend fun fetchNote(path: String): String = service.note(encPath(path)).string()

    suspend fun pushNote(path: String, content: String) {
        val res = service.putNote(encPath(path), content.toRequestBody(textMediaType))
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    suspend fun renameNote(oldPath: String, newPath: String) {
        val res = service.rename(encPath(oldPath), RenameRequest(newPath))
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    suspend fun deleteNote(path: String) {
        val res = service.delete(encPath(path))
        if (!res.isSuccessful) throw ApiException(res.code())
    }
    suspend fun search(q: String): List<SearchResult> = service.search(q)

    suspend fun fetchTags(): List<TagCount> = service.tags()

    suspend fun fetchTagged(tag: String): List<TaggedHit> = service.tagged(tag)

    suspend fun fetchTrash(): List<TrashEntry> = service.trash()

    suspend fun restoreTrash(trashName: String): String =
        service.restoreTrash(RestoreRequest(trashName)).path

    suspend fun purgeTrash(trashName: String) {
        val res = service.purgeTrash(trashName)
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    suspend fun emptyTrash() {
        val res = service.emptyTrash(1)
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    suspend fun fetchShares(path: String): List<PublicShare> = service.shares(path)

    suspend fun createShare(path: String, password: String?): PublicShare =
        service.createShare(CreateShareRequest(path, password?.takeIf { it.isNotBlank() }))

    suspend fun deleteShare(id: String) {
        val res = service.deleteShare(id)
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    companion object {
        fun encPath(path: String): String =
            path.split('/').joinToString("/") { Uri.encode(it) }
    }
}