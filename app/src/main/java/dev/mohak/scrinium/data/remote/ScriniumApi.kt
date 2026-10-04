package dev.mohak.scrinium.data.remote

import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
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

// Row shape of the server's `tasks` table (snake_case on the wire). Tasks
// live in the server's SQLite, not in .md files, so they never touch Room.
@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: String,
    val priority: String = "none",
    val area: String? = null,
    @SerialName("waiting_on") val waitingOn: String? = null,
    @SerialName("waiting_since") val waitingSince: Long? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("due_at") val dueAt: Long? = null,
    @SerialName("remind_at") val remindAt: Long? = null,
    @SerialName("link_count") val linkCount: Int = 0,
    val position: Double = 0.0,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L
)

// No defaults on purpose: the converter skips default-valued fields, and a
// missing status makes the server pick 'todo' instead of what we asked for.
@Serializable
data class NewTaskRequest(
    val title: String,
    val status: String,
    val area: String?,
    @SerialName("due_at") val dueAt: Long?,
    @SerialName("parent_id") val parentId: String?
)

@Serializable
data class TaskLinkRequest(@SerialName("note_path") val notePath: String)

@Serializable
data class AttachmentResponse(val path: String)

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

    @GET("api/tasks")
    suspend fun tasks(@Query("note") note: String? = null): List<TaskDto>

    @POST("api/tasks")
    suspend fun createTask(@Body body: NewTaskRequest): TaskDto

    // Partial update: only the keys present change; JSON null clears a field.
    @PATCH("api/tasks/{id}")
    suspend fun patchTask(@Path("id") id: String, @Body body: JsonObject): TaskDto

    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): Response<ResponseBody>

    @GET("api/tasks/{id}/links")
    suspend fun taskLinks(@Path("id") id: String): List<String>

    @POST("api/tasks/{id}/links")
    suspend fun addTaskLink(@Path("id") id: String, @Body body: TaskLinkRequest): List<String>

    @DELETE("api/tasks/{id}/links")
    suspend fun removeTaskLink(@Path("id") id: String, @Query("note_path") notePath: String): List<String>

    @Multipart
    @POST("api/attachments")
    suspend fun uploadAttachment(
        @Part file: MultipartBody.Part,
        @Part("folder") folder: RequestBody
    ): AttachmentResponse
}

class ApiException(val status: Int) : Exception("API error $status")

class ScriniumApi(
    baseUrl: String,
    tokenProvider: () -> String?,
    onUnauthorized: () -> Unit,
    cacheDir: File
) {
    private val textMediaType = "text/plain".toMediaType()
    private val root = baseUrl.trimEnd('/') + "/"

    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenProvider, onUnauthorized))
        .build()

    // Images only. The server marks assets `private, max-age=3600`, so a disk
    // cache keeps recently viewed images working offline. Kept off the note
    // client so a cached response can never stand in for note content.
    private val assetClient = client.newBuilder()
        .cache(Cache(File(cacheDir, "assets"), 50L * 1024 * 1024))
        .build()

    private val service: ScriniumService = Retrofit.Builder()
        .baseUrl(root)
        .client(client)
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

    suspend fun fetchTasks(): List<TaskDto> = service.tasks()

    suspend fun fetchTasksForNote(path: String): List<TaskDto> = service.tasks(note = path)

    suspend fun createTask(body: NewTaskRequest): TaskDto = service.createTask(body)

    suspend fun patchTask(id: String, patch: JsonObject): TaskDto = service.patchTask(Uri.encode(id), patch)

    suspend fun deleteTask(id: String) {
        val res = service.deleteTask(Uri.encode(id))
        if (!res.isSuccessful) throw ApiException(res.code())
    }

    suspend fun fetchTaskLinks(id: String): List<String> = service.taskLinks(Uri.encode(id))

    suspend fun addTaskLink(id: String, notePath: String): List<String> =
        service.addTaskLink(Uri.encode(id), TaskLinkRequest(notePath))

    suspend fun removeTaskLink(id: String, notePath: String): List<String> =
        service.removeTaskLink(Uri.encode(id), notePath)

    // Vault-relative image path -> raw bytes, through the auth-gated asset API.
    suspend fun fetchAsset(path: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(root + "api/assets/" + encPath(path)).build()
        assetClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code)
            res.body.bytes()
        }
    }

    // Returns the vault-relative path the server stored the image at.
    suspend fun uploadAttachment(bytes: ByteArray, mimeType: String, fileName: String, folder: String): String {
        val part = MultipartBody.Part.createFormData(
            "file",
            fileName,
            bytes.toRequestBody(mimeType.toMediaType())
        )
        return service.uploadAttachment(part, folder.toRequestBody(textMediaType)).path
    }

    companion object {
        fun encPath(path: String): String =
            path.split('/').joinToString("/") { Uri.encode(it) }
    }
}