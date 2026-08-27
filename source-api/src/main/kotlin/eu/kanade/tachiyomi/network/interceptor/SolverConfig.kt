package eu.kanade.tachiyomi.network.interceptor

/**
 * Anti-detect solver sidecar (Plan #3). When `MU_SOLVER_URL` is set, requests to hard hosts (see
 * [FlareSolverrConfig.fetchThroughHosts]) are run as a same-origin IN-PAGE fetch inside the sidecar's real
 * browser — which both passes Cloudflare AND returns the XHR-only data MangaFire's `/api` serves only to a
 * same-origin request (a plain FlareSolverr navigation gets an empty stub). Falls back to FlareSolverr when
 * unset or unreachable. Env-only (no Settings UI) — it's an infrastructure endpoint like the FS URL.
 */
object SolverConfig {
    @Volatile
    var url: String? = System.getenv("MU_SOLVER_URL")?.trim()?.ifBlank { null }

    val enabled: Boolean get() = !url.isNullOrBlank()
}
