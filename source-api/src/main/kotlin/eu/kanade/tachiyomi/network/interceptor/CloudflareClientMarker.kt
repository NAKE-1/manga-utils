package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Interceptor
import okhttp3.Response

/**
 * No-op marker added to [NetworkHelper.cloudflareClient]. An extension declares "this source is
 * behind Cloudflare" by building its client from the cloudflare client; this marker rides along
 * (it survives `newBuilder()`), so we can detect that choice — the same signal Suwayomi/Mihon use,
 * where the extension opts into the cloudflare-solving client.
 */
class CloudflareClientMarker : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
