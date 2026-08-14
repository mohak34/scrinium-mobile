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
data class ManifestEntry(val path: String, val updatedAt: Long, val contentHash: String)

@Serializable
data class MobileAuthRequest(val googleIdToken: String)

@Serializable
data class MobileAuthResponse(val apiToken: String, val email: String)

@Serializable
data class RenameRequest(val newPath: String)

@Serializable
data class SearchResult(val path: String, val title: String, val snippet: String)

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
    ): Response<Unit>

    @PATCH("api/notes/{path}")
    suspend fun rename(
        @Path("path", encoded = true) path: String,
        @Body body: RenameRequest
    ): Response<Unit>

    @DELETE("api/notes/{path}")
    suspend fun delete(@Path("path", encoded = true) path: String): Response<Unit>

    @GET("api/search")
    suspend fun search(@Query("q") q: String): List<SearchResult>
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

    companion object {
        fun encPath(path: String): String =
            path.split('/').joinToString("/") { Uri.encode(it) }
    }
}