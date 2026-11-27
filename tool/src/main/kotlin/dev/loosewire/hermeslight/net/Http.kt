/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. HTTP plumbing is derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Strict decoding with additive backend fields tolerated. */
val hermesJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

/**
 * Single shared client so Auth, Api, and Gateway see the same cookie jar. Gated password login
 * stores its session cookie here and the REST client attaches it automatically; loopback mode
 * instead authenticates REST with a bearer token supplied to [Api].
 */
object HermesHttp {
    val cookies: MemoryCookieJar = MemoryCookieJar()

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .cookieJar(cookies)
            .retryOnConnectionFailure(true)
            .build()
}

/** The shared app client. Tests may use [newHttpClient] for an isolated cookie jar. */
fun defaultHttpClient(): OkHttpClient = HermesHttp.client

/** A fresh client with its own memory cookie jar. */
fun newHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .cookieJar(MemoryCookieJar())
        .retryOnConnectionFailure(true)
        .build()

/**
 * Cookie jar for the dashboard session cookie. In memory only: the credential the app persists is
 * the password/token, never a cookie that could outlive the process.
 */
class MemoryCookieJar : CookieJar {
    private val entries = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries.entries.removeAll { it.value.expiresAt < now }
            cookies.forEach { cookie ->
                val key = "${cookie.domain}|${cookie.path}|${cookie.name}"
                if (cookie.expiresAt < now) {
                    entries.remove(key)
                } else {
                    entries[key] = cookie
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(entries) {
            entries.entries.removeAll { it.value.expiresAt < now }
            entries.values.filter { it.matches(url) }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/**
 * Enqueue a call and suspend until its response arrives. Cancellation cancels the in-flight request
 * so a 25-second watchdog never leaves an OkHttp worker hanging.
 */
internal suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

/** Read a response body off the caller's dispatcher. */
internal suspend fun Response.readBody(): String =
    withContext(Dispatchers.IO) {
        body?.string().orEmpty()
    }
