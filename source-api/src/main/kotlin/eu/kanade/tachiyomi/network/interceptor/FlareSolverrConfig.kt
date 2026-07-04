package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Live FlareSolverr settings, pushed in from the app's SettingsStore. Read per-request by
 * [FlareSolverrInterceptor] so toggling it in the UI takes effect without rebuilding the shared
 * OkHttp client (which is a process-wide singleton).
 */
object FlareSolverrConfig {
    @Volatile var enabled: Boolean = false
    @Volatile var url: String = "http://localhost:8191"
    @Volatile var session: String = "mangautils"
    @Volatile var sessionTtlMinutes: Int = 15
    @Volatile var timeoutMs: Long = 60_000
}
