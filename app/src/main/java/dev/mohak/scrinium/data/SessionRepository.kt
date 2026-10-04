package dev.mohak.scrinium.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.data.remote.ApiTokenDto
import dev.mohak.scrinium.data.remote.ScriniumApi
import dev.mohak.scrinium.reminders.Reminders
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRepository(
    context: Context,
    private val tokenStore: SecureTokenStore,
    private val api: ScriniumApi,
    private val reminders: Reminders
) {
    private val credentialManager = CredentialManager.create(context)

    private val _signedIn = MutableStateFlow(false)
    private val _email = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    val email: StateFlow<String?> = _email.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val lastSyncAt: StateFlow<Long> = tokenStore.lastSyncAt

    init {
        _signedIn.value = tokenStore.token.value != null
        _email.value = tokenStore.email.value
    }

    fun isSignedIn(): Boolean = _signedIn.value

    suspend fun signIn(activityContext: Context): Boolean {
        if (BuildConfig.SCRINIUM_GOOGLE_CLIENT_ID.isBlank()) {
            _error.value =
                "Google client ID is not configured. Put scriniumGoogleClientId in local.properties and rebuild."
            return false
        }
        _busy.value = true
        _error.value = null
        try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(
                    GetGoogleIdOption.Builder()
                        .setServerClientId(BuildConfig.SCRINIUM_GOOGLE_CLIENT_ID)
                        .setFilterByAuthorizedAccounts(false)
                        .build()
                )
                .build()
            val response = credentialManager.getCredential(activityContext, request)
            val idToken = googleIdToken(response)
            if (idToken == null) {
                _error.value = "No Google ID token returned"
                return false
            }
            val result = api.exchangeGoogleIdToken(idToken)
            tokenStore.setToken(result.apiToken)
            tokenStore.setEmail(result.email)
            _email.value = result.email
            _signedIn.value = true
            return true
        } catch (e: GetCredentialCancellationException) {
            return false
        } catch (e: GetCredentialException) {
            _error.value = e.message ?: "Google sign-in failed"
            return false
        } catch (e: Exception) {
            _error.value = "Sign-in failed: ${e.message}"
            return false
        } finally {
            _busy.value = false
        }
    }

    suspend fun updateLastSyncAt(value: Long) {
        tokenStore.setLastSyncAt(value)
    }

    suspend fun forceSignOut() {
        reminders.clearAll()
        tokenStore.setToken(null)
        tokenStore.setEmail(null)
        _email.value = null
        _signedIn.value = false
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    // Signed-in devices (API tokens) for this account. The server stores
    // only SHA-256 hashes, so hashing our own token finds this phone's row.
    suspend fun fetchDevices(): List<ApiTokenDto> = api.fetchTokens()

    suspend fun revokeDevice(tokenHash: String) = api.revokeToken(tokenHash)

    fun currentTokenHash(): String? = tokenStore.token.value?.let { raw ->
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun clearError() {
        _error.value = null
    }

    private fun googleIdToken(response: GetCredentialResponse): String? {
        val credential = response.credential
        return if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else {
            null
        }
    }
}