package com.leaflock.nfctap

import android.app.Application
import android.util.Log
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.log.LogLevel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LeafLockApp : Application() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        TerminalApplicationDelegate.onCreate(this)

        if (!Terminal.isInitialized()) {
            try {
                Terminal.init(
                    applicationContext,
                    LogLevel.VERBOSE,
                    object : ConnectionTokenProvider {
                        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
                            try {
                                val req = Request.Builder()
                                    .url("${BuildConfig.POS_API_BASE}/api/stripe/connection_token")
                                    .post("{}".toRequestBody("application/json".toMediaType()))
                                    .build()
                                http.newCall(req).execute().use { resp ->
                                    val body = resp.body?.string().orEmpty()
                                    if (!resp.isSuccessful) {
                                        callback.onFailure(
                                            ConnectionTokenException("HTTP ${resp.code}: $body")
                                        )
                                        return
                                    }
                                    val secret = JSONObject(body).getString("secret")
                                    callback.onSuccess(secret)
                                }
                            } catch (e: Exception) {
                                Log.e("LeafLockApp", "connection token failed", e)
                                callback.onFailure(
                                    ConnectionTokenException(e.message ?: "token error", e)
                                )
                            }
                        }
                    },
                    object : TerminalListener {}
                )
            } catch (e: Exception) {
                Log.e("LeafLockApp", "Terminal.init failed", e)
            }
        }
    }
}
