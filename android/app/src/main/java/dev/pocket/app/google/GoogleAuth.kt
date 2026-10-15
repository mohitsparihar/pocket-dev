package dev.pocket.app.google

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Sign-in uses the same public Cloud SDK OAuth client as `gcloud` and Termux.
 * That client is already allowed to request the Cloud scope. The consent screen
 * therefore says Google Cloud SDK.
 */
class GoogleAuth(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = encryptedPrefs(appContext)
    private val account = MutableStateFlow(prefs.getString(KEY_EMAIL, null))
    private var accessToken: String? = null
    private var accessExpiryMs: Long = 0

    fun account(): StateFlow<String?> = account

    fun email(): String? = account.value

    suspend fun signIn(activity: Activity): String {
        check(CLIENT_ID.isNotBlank()) {
            "Google sign-in isn't set up in this build. Add pocket.googleClientId and pocket.googleClientSecret to android/local.properties and rebuild."
        }
        val port = 40_000 + SecureRandom().nextInt(20_000)
        val redirect = "http://localhost:$port/"
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }.toUrlBase64()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).toUrlBase64()
        val authUrl = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "select_account consent")
            .build()
        val code = awaitAuthCode(activity, authUrl.toString())
        return withContext(Dispatchers.IO) {
            val payload = postToken(
                FormBody.Builder()
                    .add("code", code)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("redirect_uri", redirect)
                    .add("grant_type", "authorization_code")
                    .add("code_verifier", verifier)
                    .build(),
            )
            store(payload)
            account.value ?: throw IllegalStateException("Signed in, but Google did not return an email")
        }
    }

    /**
     * Google sends the browser to http://localhost after consent. This phone's DNS
     * has no address for that name, so the page is caught here and never loaded.
     */
    private suspend fun awaitAuthCode(activity: Activity, authUrl: String): String =
        suspendCancellableCoroutine { cont ->
            activity.runOnUiThread {
                val webView = WebView(activity)
                val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
                var finished = false
                fun finish(result: Result<String>) {
                    if (finished || !cont.isActive) return
                    finished = true
                    runCatching {
                        webView.stopLoading()
                        dialog.dismiss()
                        webView.destroy()
                    }
                    result.fold(cont::resume) { cont.resumeWithException(it) }
                }
                fun consume(uri: Uri): Boolean {
                    val host = uri.host ?: return false
                    if (host != "localhost" && host != "127.0.0.1") return false
                    val error = uri.getQueryParameter("error")
                    val code = uri.getQueryParameter("code")
                    when {
                        error != null -> finish(Result.failure(IllegalStateException("Google sign-in was cancelled ($error)")))
                        code.isNullOrBlank() -> finish(Result.failure(IllegalStateException("Google did not return a sign-in code")))
                        else -> finish(Result.success(code))
                    }
                    return true
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = WebSettings.getDefaultUserAgent(activity).replace("; wv", "")
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        consume(request.url)

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && consume(request.url)) return
                        if (request.isForMainFrame) {
                            finish(Result.failure(IllegalStateException(error.description?.toString() ?: "Google sign-in page failed")))
                        }
                    }
                }
                dialog.setOnCancelListener {
                    finish(Result.failure(IllegalStateException("Sign-in was not finished. Choose the Google account that can start this VM.")))
                }
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        if (webView.canGoBack()) webView.goBack() else dialog.cancel()
                        true
                    } else {
                        false
                    }
                }
                dialog.setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                webView.loadUrl(authUrl)
                cont.invokeOnCancellation {
                    activity.runOnUiThread {
                        finished = true
                        runCatching {
                            webView.stopLoading()
                            dialog.dismiss()
                            webView.destroy()
                        }
                    }
                }
            }
        }

    fun accessToken(): String {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < accessExpiryMs - 60_000) return current
        val refresh = prefs.getString(KEY_REFRESH, null)
            ?: throw IllegalStateException("Sign in with Google first")
        val payload = postToken(
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build(),
        )
        store(payload, keepRefresh = true)
        return accessToken ?: throw IllegalStateException("Google did not return an access token")
    }

    fun signOut() {
        prefs.edit().clear().apply()
        accessToken = null
        account.value = null
    }

    private fun store(payload: kotlinx.serialization.json.JsonObject, keepRefresh: Boolean = false) {
        val token = payload["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException(payload["error_description"]?.jsonPrimitive?.content ?: "Google token exchange failed")
        val expires = payload["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        accessToken = token
        accessExpiryMs = System.currentTimeMillis() + expires * 1000
        val editor = prefs.edit()
        payload["refresh_token"]?.jsonPrimitive?.content?.let { editor.putString(KEY_REFRESH, it) }
        if (!keepRefresh) {
            val email = runCatching { fetchEmail(token) }.getOrNull()
            if (email != null) {
                editor.putString(KEY_EMAIL, email)
                account.value = email
            }
        }
        editor.apply()
    }

    private fun fetchEmail(token: String): String {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Could not read the Google account")
            return json.parseToJsonElement(text).jsonObject["email"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Google account has no email")
        }
    }

    private fun postToken(body: FormBody): kotlinx.serialization.json.JsonObject {
        val request = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val payload = json.parseToJsonElement(text).jsonObject
            if (!response.isSuccessful) {
                throw IllegalStateException(payload["error_description"]?.jsonPrimitive?.content ?: "Google sign-in failed")
            }
            return payload
        }
    }

    private fun ByteArray.toUrlBase64() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    companion object {
        // From android/local.properties (pocket.googleClientId / pocket.googleClientSecret); see README.
        private val CLIENT_ID = dev.pocket.app.BuildConfig.GOOGLE_CLIENT_ID
        private val CLIENT_SECRET = dev.pocket.app.BuildConfig.GOOGLE_CLIENT_SECRET
        private const val SCOPE = "openid email https://www.googleapis.com/auth/cloud-platform"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EMAIL = "email"

        private fun encryptedPrefs(context: Context) = try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                "pocket-google",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences("pocket-google-plain", Context.MODE_PRIVATE)
        }
    }
}
