package dev.mohak.scrinium.data.remote

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit
) : Interceptor {
    // The token a 401 already signed out, so each session signs out once and
    // a late 401 for an old token can't end the session that replaced it.
    private var signedOut: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        val response = chain.proceed(request)
        if (response.code == 401 && token != null) {
            val first = synchronized(this) {
                (token == tokenProvider() && token != signedOut).also { if (it) signedOut = token }
            }
            if (first) onUnauthorized()
        }
        return response
    }
}