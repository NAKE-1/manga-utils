// Typed client for the Ktor backend. IDs are strings (the server serializes Longs as strings).

export interface Source { id: string; name: string; lang: string; nsfw: boolean; cfState: 'green' | 'red' | 'orange'; down: boolean; imagesDown: boolean; usesWebView: boolean }
export interface SourcePref { index: number; key: string | null; title: string; summary: string | null; type: string; value: string; entries: string[] | null; entryValues: string[] | null; enabled: boolean }

export interface Manga {
  sourceId: string
  url: string
  title: string
  thumbnailUrl?: string | null
  author?: string | null
  artist?: string | null
  description?: string | null
  genre?: string | null
  status: number
}

export interface PageResult { mangas: Manga[]; hasNextPage: boolean }

export interface Chapter {
  url: string
  name: string
  scanlator?: string | null
  dateUpload: number
  number: number
  downloaded: boolean
}

export interface Detail { manga: Manga; chapters: Chapter[]; newChapters: string[] }

export interface MangaState { inLibrary: boolean; bookmarked: boolean; read: string[]; bookmarks: string[] }

export interface LibraryEntry {
  sourceId: string
  url: string
  title: string
  thumbnailUrl?: string | null
  author?: string | null
  status: number
  newChapters: number
  lastNumber: number
  lastName: string
  lastDate: number
  downloadedChapters: number
  totalChapters: number
}

/** Cover download-status badge: green when all chapters are downloaded, yellow for some, none otherwise. */
export function dlState(e: { downloadedChapters?: number; totalChapters?: number }): 'all' | 'some' | undefined {
  const dl = e.downloadedChapters ?? 0, total = e.totalChapters ?? 0
  if (dl <= 0) return undefined
  return total > 0 && dl >= total ? 'all' : 'some'
}

/** True when a source error is just the in-app WebView (Chromium) still starting on first use —
 *  transient, so the UI can show a "starting…" state and auto-retry instead of a hard error. */
export function isWebViewWarmup(msg: string | null | undefined): boolean {
  if (!msg) return false
  return /downloading\/starting Chromium|in-app WebView/i.test(msg)
}

export interface HistoryItem {
  sourceId: string
  mangaUrl: string
  mangaTitle: string
  thumbnailUrl?: string | null
  chapterUrl: string
  chapterName: string
  readAt: number
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

// Fetch JSON with a timeout and a couple of retries (transient network / 5xx) for resilience.
async function getJson<T>(url: string, retries = 2, timeoutMs = 15000, signal?: AbortSignal): Promise<T> {
  let lastErr: Error = new Error('Request failed')
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError') // caller navigated away
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), timeoutMs)
    const onExt = () => ctrl.abort() // external cancel (navigation) → abort this fetch, closing the socket
    signal?.addEventListener('abort', onExt)
    try {
      const r = await fetch(url, { signal: ctrl.signal })
      if (r.ok) return (await r.json()) as T
      // Surface the backend's reason (e.g. "Cloudflare protection… (HTTP 403)", "HTTP error 522").
      let msg = ''
      try { const b = await r.json(); if (b && typeof b.error === 'string') msg = b.error } catch { /* non-JSON body */ }
      lastErr = new Error(msg || `Request failed (${r.status})`)
      // 502 = the source itself errored (down / Cloudflare) — retrying won't help and is slow.
      if (r.status >= 500 && r.status !== 502 && attempt < retries) { await sleep(400 * (attempt + 1)); continue }
      throw lastErr
    } catch (e) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError') // intentional cancel — don't retry
      if (e === lastErr) throw e // a definitive HTTP error we already decided not to retry
      lastErr = ctrl.signal.aborted ? new Error('The request timed out.') : (e instanceof Error ? e : new Error('Network error'))
      if (attempt < retries) { await sleep(400 * (attempt + 1)); continue }
      throw lastErr
    } finally {
      clearTimeout(t)
      signal?.removeEventListener('abort', onExt)
    }
  }
  throw lastErr
}

export const api = {
  sources: () => getJson<Source[]>('/api/sources'),
  sourcePrefs: (id: string) => getJson<SourcePref[]>(`/api/sources/${id}/preferences`),
  setSourcePref: async (id: string, index: number, value: string) => {
    const r = await fetch(`/api/sources/${id}/preferences`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ index, value }) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Failed to save')
  },
  languages: () => getJson<string[]>('/api/languages'),
  library: () => getJson<LibraryEntry[]>('/api/library'),
  updateLibrary: () => fetch('/api/library/update', { method: 'POST' }).then((r) => r.json() as Promise<{ newChapters: number; updatedManga: number; titles: { title: string; count: number }[] }>),
  updateProgress: () => getJson<{ done: number; total: number; running: boolean }>('/api/library/update/progress'),
  history: () => getJson<HistoryItem[]>('/api/history'),
  popular: (id: string, page = 1, signal?: AbortSignal) => getJson<PageResult>(`/api/sources/${id}/popular?page=${page}`, 2, 15000, signal),
  latest: (id: string, page = 1, signal?: AbortSignal) => getJson<PageResult>(`/api/sources/${id}/latest?page=${page}`, 2, 15000, signal),
  search: (id: string, q: string, page = 1, signal?: AbortSignal) =>
    getJson<PageResult>(`/api/sources/${id}/search?q=${encodeURIComponent(q)}&page=${page}`, 2, 15000, signal),
  detail: (id: string, url: string, refresh = false, signal?: AbortSignal) =>
    getJson<Detail>(`/api/sources/${id}/manga?url=${encodeURIComponent(url)}${refresh ? '&refresh=true' : ''}`, 2, 15000, signal),
  mangaState: (id: string, url: string) =>
    getJson<MangaState>(`/api/manga/state?source=${id}&url=${encodeURIComponent(url)}`),
  addLibrary: (id: string, url: string) =>
    fetch(`/api/library?source=${id}&url=${encodeURIComponent(url)}`, { method: 'POST' }),
  removeLibrary: (id: string, url: string) =>
    fetch(`/api/library?source=${id}&url=${encodeURIComponent(url)}`, { method: 'DELETE' }),
  setMangaBookmark: (id: string, url: string, on: boolean) =>
    fetch(`/api/manga/bookmark?source=${id}&url=${encodeURIComponent(url)}&on=${on}`, { method: 'POST' }),
  downloadCount: (title: string) => getJson<{ count: number }>(`/api/downloads/count?title=${encodeURIComponent(title)}`),
  deleteDownloads: (title: string) => fetch(`/api/downloads?title=${encodeURIComponent(title)}`, { method: 'DELETE' }),
  pages: (id: string, chapter: string, title?: string, name?: string) => {
    let u = `/api/chapter/pages?source=${id}&chapter=${encodeURIComponent(chapter)}`
    if (title) u += `&title=${encodeURIComponent(title)}`
    if (name) u += `&name=${encodeURIComponent(name)}`
    return getJson<{ count: number }>(u)
  },
  recordHistory: (id: string, manga: string, chapter: string, title: string, name: string, thumb?: string | null) =>
    fetch(`/api/history?source=${id}&manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(chapter)}&title=${encodeURIComponent(title)}&name=${encodeURIComponent(name)}${thumb ? `&thumb=${encodeURIComponent(thumb)}` : ''}`, { method: 'POST' }),
  setRead: (id: string, manga: string, chapter: string, read: boolean) =>
    fetch(`/api/read?source=${id}&manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(chapter)}&read=${read}`, { method: 'POST' }),
  setBookmark: (id: string, manga: string, chapter: string, on: boolean) =>
    fetch(`/api/bookmarks?source=${id}&manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(chapter)}&on=${on}`, { method: 'POST' }),

  getSettings: () => getJson<SettingsInfo>('/api/settings'),
  flaresolverrTest: (url?: string) => getJson<{ ok: boolean; version?: string; error?: string }>(`/api/flaresolverr/test${url ? `?url=${encodeURIComponent(url)}` : ''}`),
  flaresolverrEvents: (since?: number) => getJson<{ lastId: number; events: { id: number; host: string; phase: string; cookies: number }[] }>(`/api/flaresolverr/events${since != null ? `?since=${since}` : ''}`),
  backupPreview: async (data: ArrayBuffer) => {
    const r = await fetch('/api/backup/preview', { method: 'POST', body: data })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Preview failed')
    return r.json() as Promise<{ total: number; manga: { title: string; source: string; chapters: number; read: number; inLibrary: boolean }[]; hasSettings?: boolean; repos?: number; extensions?: number }>
  },
  importBackup: async (data: ArrayBuffer) => {
    const r = await fetch('/api/backup/import', { method: 'POST', body: data })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Import failed')
    return r.json() as Promise<{ imported: number; skipped: number; total: number; settingsRestored?: boolean; reposAdded?: number; extensionsInstalled?: number; extensionsFailed?: number; historyRestored?: number }>
  },
  saveSettings: async (patch: Partial<{ downloadDir: string | null; downloadAsCbz: boolean; downloadConcurrency: number; parallelDownloads: number; perSourceParallel: boolean; visibleLanguages: string[]; autoUpdate: boolean; autoUpdateHours: number; autoDownloadNew: boolean; flareSolverrEnabled: boolean; flareSolverrUrl: string; flareSolverrSession: string; flareSolverrSessionTtlMinutes: number; flareSolverrTimeoutMs: number; usbBackupDir: string }>): Promise<SettingsInfo> => {
    const r = await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Save failed')
    return r.json()
  },
  backupToUsb: async (): Promise<BackupJob> => {
    const r = await fetch('/api/dyno/backup-now', { method: 'POST' })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Backup failed to start')
    return r.json()
  },
  usbBackupProgress: () => getJson<BackupJob>('/api/dyno/backup/progress'),
  diag: (id: string) => getJson<DiagResult>(`/api/diag?source=${id}`, 0, 30000),
  devStats: () => getJson<DevStats>('/api/dev/stats'),
  version: () => getJson<VersionInfo>('/api/version'),
  simulateUpdate: (source: string, manga: string) => fetch(`/api/dev/simulate-update?source=${source}&manga=${encodeURIComponent(manga)}`, { method: 'POST' }).then((r) => r.json() as Promise<{ title: string; newChapters: number; autoDownloaded: boolean }>),
  deleteHistory: (id: string, manga: string) =>
    fetch(`/api/history?source=${id}&manga=${encodeURIComponent(manga)}`, { method: 'DELETE' }),
  clearHistory: () => fetch('/api/history/clear', { method: 'POST' }),
  clearNewChapters: () => fetch('/api/library/clear-new', { method: 'POST' }).then((r) => r.json() as Promise<{ count: number }>),

  // Extensions + repositories
  extensions: () => getJson<ExtInstalled[]>('/api/extensions'),
  extCheckUpdates: () => fetch('/api/extensions/check-updates', { method: 'POST' }).then((r) => r.json() as Promise<string[]>),
  extAvailable: (q: string) => getJson<ExtAvailable[]>(`/api/extensions/available?q=${encodeURIComponent(q)}`, 0, 60000),
  extInstall: async (pkg: string) => {
    const r = await fetch('/api/extensions/install', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pkg }) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Install failed')
    return r.json()
  },
  extUninstall: (pkg: string) => fetch(`/api/extensions?pkg=${encodeURIComponent(pkg)}`, { method: 'DELETE' }),
  repos: () => getJson<string[]>('/api/repos'),
  addRepo: async (url: string): Promise<string[]> => {
    const r = await fetch('/api/repos', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Failed to add repo')
    return r.json()
  },
  removeRepo: (url: string) => fetch(`/api/repos?url=${encodeURIComponent(url)}`, { method: 'DELETE' }).then((r) => r.json() as Promise<string[]>),

  downloads: () => getJson<Downloads>('/api/downloads'),
  enqueueDownload: (source: string, manga: string, title: string, chapters: { url: string; name: string }[]) =>
    fetch('/api/downloads', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source, manga, title, chapters }) }),
  stopDownload: (id: string) => fetch(`/api/downloads/stop?id=${encodeURIComponent(id)}`, { method: 'POST' }),
  stopAllDownloads: () => fetch('/api/downloads/stop-all', { method: 'POST' }),
  clearDownloads: () => fetch('/api/downloads/clear', { method: 'POST' }),

  // Download manager (on-disk content)
  manageDownloads: () => getJson<ManagedSeries[]>('/api/downloads/manage'),
  manageChapters: (title: string) => getJson<ManagedChapter[]>(`/api/downloads/manage/chapters?title=${encodeURIComponent(title)}`),
  deleteDownloadChapter: (title: string, chapter: string) => fetch(`/api/downloads/chapter?title=${encodeURIComponent(title)}&chapter=${encodeURIComponent(chapter)}`, { method: 'DELETE' }),
  markSeriesUnread: (title: string) => fetch(`/api/downloads/manage/mark-unread?title=${encodeURIComponent(title)}`, { method: 'POST' }).then((r) => r.json() as Promise<{ count: number }>),
  deleteIncomplete: (title: string) => fetch(`/api/downloads/manage/delete-incomplete?title=${encodeURIComponent(title)}`, { method: 'POST' }).then((r) => r.json() as Promise<{ count: number }>),
  repairDownloads: (title: string) => fetch(`/api/downloads/manage/repair?title=${encodeURIComponent(title)}`, { method: 'POST' }).then((r) => r.json() as Promise<{ count: number }>),
}

export interface ManagedSeries { title: string; chapters: number; incomplete: number; bytes: number; hasCover: boolean }
export interface ManagedChapter { name: string; pages: number; bytes: number; cbz: boolean; complete: boolean }

export interface DlChapterRef { url: string; name: string }
export interface DlTask {
  id: string; mangaKey: string; mangaTitle: string; state: string
  total: number; done: number; failed: number
  currentChapter: string; currentChapterUrl: string; pagesDone: number; pagesTotal: number
  kbps: number; error: string; failedChapters: DlChapterRef[]
}
export interface Downloads { tasks: DlTask[]; active: number; queued: number; totalKbps: number }

export interface ExtInstalled { pkg: string; name: string; version: string; lang: string; nsfw: boolean; sources: number; repo: string; usesWebView: boolean }
export interface ExtAvailable { pkg: string; name: string; version: string; lang: string; nsfw: boolean; installed: boolean; hasUpdate: boolean; repo: string }

export interface SettingsInfo { downloadDir: string | null; effectiveDownloadDir: string; dataDir: string; downloadAsCbz: boolean; downloadConcurrency: number; parallelDownloads: number; perSourceParallel: boolean; visibleLanguages: string[]; cloudflareBypass: boolean; autoUpdate: boolean; autoUpdateHours: number; autoDownloadNew: boolean; flareSolverrEnabled: boolean; flareSolverrUrl: string; flareSolverrSession: string; flareSolverrSessionTtlMinutes: number; flareSolverrTimeoutMs: number; usbBackupDir: string }
export interface BackupJob { running: boolean; state: string; phase: string; filesDone: number; filesTotal: number; bytesCopied: number; blobName: string; filesSkipped: number; error: string; target: string }
export interface DiagResult { source: string; baseUrl: string; pingMs: number; speedMbps: number; sampleBytes: number; ok: boolean; error?: string | null }
export interface DevStats {
  pid: number; uptimeMs: number; processRssMb: number
  heapUsedMb: number; heapCommittedMb: number; heapMaxMb: number; nonHeapUsedMb: number
  systemRamUsedMb: number; systemRamTotalMb: number
  processCpuPct: number
  threads: number; activeDownloads: number; queuedDownloads: number; installedSources: number
  jvm: string; os: string
}

export interface VersionInfo {
  version: string; commit: string; buildTime: string
  tech: { role: string; tech: string }[]
  changelog: { sha: string; date: string; subject: string }[]
}

export const STATUS_LABELS: Record<number, string> = {
  0: '', 1: 'Ongoing', 2: 'Completed', 3: 'Licensed', 4: 'Publishing finished', 5: 'Cancelled', 6: 'Hiatus',
}

// Image URLs (streamed by the backend).
export const coverUrl = (sourceId: string, url?: string | null, title?: string) =>
  url ? `/img/cover?source=${sourceId}&url=${encodeURIComponent(url)}${title ? `&title=${encodeURIComponent(title)}` : ''}` : ''

export const pageUrl = (sourceId: string, chapter: string, index: number, title?: string, name?: string) => {
  let u = `/img/page?source=${sourceId}&chapter=${encodeURIComponent(chapter)}&index=${index}`
  if (title) u += `&title=${encodeURIComponent(title)}`
  if (name) u += `&name=${encodeURIComponent(name)}`
  return u
}

/** Best-effort media-type from genres (Atsumaru-style badge). */
export function mediaType(genre?: string | null): 'manga' | 'manhwa' | 'manhua' | null {
  const g = (genre || '').toLowerCase()
  if (g.includes('manhwa') || g.includes('webtoon')) return 'manhwa'
  if (g.includes('manhua')) return 'manhua'
  if (g.includes('manga')) return 'manga'
  return null
}
