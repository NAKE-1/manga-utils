// Typed client for the Ktor backend. IDs are strings (the server serializes Longs as strings).

export interface Source { id: string; name: string; lang: string; nsfw: boolean; cfState: 'green' | 'red' | 'orange'; down: boolean }

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
async function getJson<T>(url: string, retries = 2, timeoutMs = 15000): Promise<T> {
  let lastErr: Error = new Error('Request failed')
  for (let attempt = 0; attempt <= retries; attempt++) {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), timeoutMs)
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
      if (e === lastErr) throw e // a definitive HTTP error we already decided not to retry
      lastErr = ctrl.signal.aborted ? new Error('The request timed out.') : (e instanceof Error ? e : new Error('Network error'))
      if (attempt < retries) { await sleep(400 * (attempt + 1)); continue }
      throw lastErr
    } finally {
      clearTimeout(t)
    }
  }
  throw lastErr
}

export const api = {
  sources: () => getJson<Source[]>('/api/sources'),
  languages: () => getJson<string[]>('/api/languages'),
  library: () => getJson<LibraryEntry[]>('/api/library'),
  updateLibrary: () => fetch('/api/library/update', { method: 'POST' }).then((r) => r.json() as Promise<{ newChapters: number; updatedManga: number }>),
  history: () => getJson<HistoryItem[]>('/api/history'),
  popular: (id: string, page = 1) => getJson<PageResult>(`/api/sources/${id}/popular?page=${page}`),
  latest: (id: string, page = 1) => getJson<PageResult>(`/api/sources/${id}/latest?page=${page}`),
  search: (id: string, q: string, page = 1) =>
    getJson<PageResult>(`/api/sources/${id}/search?q=${encodeURIComponent(q)}&page=${page}`),
  detail: (id: string, url: string, refresh = false) =>
    getJson<Detail>(`/api/sources/${id}/manga?url=${encodeURIComponent(url)}${refresh ? '&refresh=true' : ''}`),
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
  saveSettings: async (patch: Partial<{ downloadDir: string | null; downloadAsCbz: boolean; downloadConcurrency: number; visibleLanguages: string[] }>): Promise<SettingsInfo> => {
    const r = await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error || 'Save failed')
    return r.json()
  },
  diag: (id: string) => getJson<DiagResult>(`/api/diag?source=${id}`, 0, 30000),
  deleteHistory: (id: string, manga: string) =>
    fetch(`/api/history?source=${id}&manga=${encodeURIComponent(manga)}`, { method: 'DELETE' }),

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
}

export interface ExtInstalled { pkg: string; name: string; version: string; lang: string; nsfw: boolean; sources: number }
export interface ExtAvailable { pkg: string; name: string; version: string; lang: string; nsfw: boolean; installed: boolean; hasUpdate: boolean }

export interface SettingsInfo { downloadDir: string | null; effectiveDownloadDir: string; dataDir: string; downloadAsCbz: boolean; downloadConcurrency: number; visibleLanguages: string[]; cloudflareBypass: boolean }
export interface DiagResult { source: string; baseUrl: string; pingMs: number; speedMbps: number; sampleBytes: number; ok: boolean; error?: string | null }

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
