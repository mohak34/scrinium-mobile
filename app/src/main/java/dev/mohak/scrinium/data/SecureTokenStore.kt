package dev.mohak.scrinium.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "scrinium")

class SecureTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val key: SecretKey = getOrCreateKey()

    val token = MutableStateFlow<String?>(null)
    val email = MutableStateFlow<String?>(null)
    val lastSyncAt = MutableStateFlow(0L)

    init {
        val prefs = runBlocking { appContext.dataStore.data.first() }
        token.value = prefs[KEY_TOKEN]?.let { decrypt(it) }
        email.value = prefs[KEY_EMAIL]
        lastSyncAt.value = prefs[KEY_LAST_SYNC] ?: 0L
    }

    suspend fun setToken(value: String?) {
        token.value = value
        appContext.dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_TOKEN) else prefs[KEY_TOKEN] = encrypt(value)
        }
    }

    suspend fun setEmail(value: String?) {
        email.value = value
        appContext.dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_EMAIL) else prefs[KEY_EMAIL] = value
        }
    }

    suspend fun setLastSyncAt(value: Long) {
        lastSyncAt.value = value
        appContext.dataStore.edit { prefs -> prefs[KEY_LAST_SYNC] = value }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_SIZE)
            val ciphertext = raw.copyOfRange(IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "scrinium_api_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val GCM_TAG_LENGTH = 128
        private val KEY_TOKEN = stringPreferencesKey("api_token")
        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
    }
}