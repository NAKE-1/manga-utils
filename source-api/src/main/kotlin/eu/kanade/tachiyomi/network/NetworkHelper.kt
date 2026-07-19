package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareClientMarker
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrInterceptor
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NetworkHelper(
    context: Context,
) {
    //    private val preferences: PreferencesHelper by injectLazy()

//    private val cacheDir = File(context.cacheDir, "network_cache")

//    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    // Tachidesk -->
    val cookieStore = PersistentCookieStore(context)

    init {
        CookieHandler.setDefault(
            CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL),
        )
    }
    // Tachidesk <--

    private val userAgent =
        MutableStateFlow(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
    val userAgentFlow = userAgent.asStateFlow()

    fun defaultUserAgentProvider(): String = userAgent.value

    private val baseClientBuilder: OkHttpClient.Builder
        get() {
            val builder =
                OkHttpClient
                    .Builder()
                    .cookieJar(PersistentCookieJar(cookieStore))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    // Was 2 min. Some extensions (comix) spin up an in-app WebView that can hang; a
                    // shorter ceiling makes them fail fast instead of stalling browse/search for 2 min.
                    .callTimeout(45, TimeUnit.SECONDS)
                    .cache(
                        Cache(
                            directory = Files.createTempDirectory("tachidesk_network_cache").toFile(),
                            maxSize = 5L * 1024 * 1024, // 5 MiB
                        ),
                    ).addInterceptor(UncaughtExceptionInterceptor())
                    .addInterceptor(FlareSolverrInterceptor(cookieStore) { ua -> userAgent.value = ua })
                    // Compatibility shim (no-op): some extensions (Asura/Arena/MangaThemesia-based) assert a
                    // CloudflareInterceptor is present in the default client and error() otherwise. Real CF
                    // handling is FlareSolverr's job above; this only satisfies their presence check.
                    .addInterceptor(CloudflareInterceptor())
                    .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
                    .addNetworkInterceptor(IgnoreGzipInterceptor())
                    .addNetworkInterceptor(BrotliInterceptor)
                    // Force the FlareSolverr-solved User-Agent on cleared hosts LAST (after any
                    // extension interceptor + the cookie bridge), so cf_clearance stays valid.
                    .addNetworkInterceptor { chain ->
                        val req = chain.request()
                        val ua = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig.solvedUserAgents[req.url.host]
                        chain.proceed(
                            if (ua != null && req.header("User-Agent") != ua) {
                                req.newBuilder().header("User-Agent", ua).build()
                            } else {
                                req
                            },
                        )
                    }

            // if (preferences.verboseLogging().get()) {
            val httpLoggingInterceptor =
                HttpLoggingInterceptor(
                    object : HttpLoggingInterceptor.Logger {
                        val logger = KotlinLogging.logger { }

                        override fun log(message: String) {
                            logger.debug { message }
                        }
                    },
                ).apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
            // }

            // Cloudflare solving is handled by FlareSolverrInterceptor (added above); the no-op
            // CloudflareInterceptor there is only a presence shim for extensions that require it.

            // when (preferences.dohProvider().get()) {
            //     PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            //     PREF_DOH_GOOGLE -> builder.dohGoogle()
            //     PREF_DOH_ADGUARD -> builder.dohAdGuard()
            //     PREF_DOH_QUAD9 -> builder.dohQuad9()
            //     PREF_DOH_ALIDNS -> builder.dohAliDNS()
            //     PREF_DOH_DNSPOD -> builder.dohDNSPod()
            //     PREF_DOH_360 -> builder.doh360()
            //     PREF_DOH_QUAD101 -> builder.dohQuad101()
            //     PREF_DOH_MULLVAD -> builder.dohMullvad()
            //     PREF_DOH_CONTROLD -> builder.dohControlD()
            //     PREF_DOH_NJALLA -> builder.dohNajalla()
            //     PREF_DOH_SHECAN -> builder.dohShecan()
            // }

            return builder
        }

//    val client by lazy { baseClientBuilder.cache(Cache(cacheDir, cacheSize)).build() }
    val client by lazy { baseClientBuilder.build() }

    // Distinct from [client] only by a no-op marker, so we can tell which sources opted into it
    // (i.e. which the extension author flagged as Cloudflare-protected).
    val cloudflareClient by lazy { client.newBuilder().addInterceptor(CloudflareClientMarker()).build() }
}
