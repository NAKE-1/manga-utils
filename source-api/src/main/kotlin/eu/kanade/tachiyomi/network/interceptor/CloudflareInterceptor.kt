package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Compatibility shim. Several MangaThemesia / Keiyoushi-based extensions (Asura Scans, Arena Scans, …)
 * build their own client only after asserting a `CloudflareInterceptor` is present in the app's default
 * client — `client.interceptors.filterIsInstance<CloudflareInterceptor>().firstOrNull()
 * ?: error("CloudflareInterceptor must be present in default client")`. Without it they can't fetch
 * filters/search/popular at all.
 *
 * We handle Cloudflare via [FlareSolverrInterceptor], which already runs earlier in the same chain, so
 * this class is intentionally a **no-op** — it exists only to satisfy that presence check. Since it's an
 * application interceptor on the default client, it also rides along into any client an extension derives
 * via `newBuilder()`, and the real solving continues to come from FlareSolverr.
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
