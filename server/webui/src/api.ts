// Typed client for the Ktor backend. IDs are strings (the server serializes Longs as strings).

export interface Source { id: string; name: string; lang: string }

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
}

export interface Detail { manga: Manga; chapters: Chapter[] }

export interface MangaState { inLibrary: boolean; read: string[]; bookmarks: string[] }

export interface LibraryEntry {
  sourceId: string
  url: string
  title: string
  thumbnailUrl?: string | null
  author?: string | null
  status: number
  newChapters: number
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

async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url)
  if (!r.ok) throw new Error(`${r.status} ${url}`)
  return r.json() as Promise<T>
}

export const api = {
  sources: () => getJson<Source[]>('/api/sources'),
  library: () => getJson<LibraryEntry[]>('/api/library'),
  history: () => getJson<HistoryItem[]>('/api/history'),
  popular: (id: string, page = 1) => getJson<PageResult>(`/api/sources/${id}/popular?page=${page}`),
  latest: (id: string, page = 1) => getJson<PageResult>(`/api/sources/${id}/latest?page=${page}`),
  search: (id: string, q: string, page = 1) =>
    getJson<PageResult>(`/api/sources/${id}/search?q=${encodeURIComponent(q)}&page=${page}`),
  detail: (id: string, url: string) =>
    getJson<Detail>(`/api/sources/${id}/manga?url=${encodeURIComponent(url)}`),
  mangaState: (id: string, url: string) =>
    getJson<MangaState>(`/api/manga/state?source=${id}&url=${encodeURIComponent(url)}`),
  addLibrary: (id: string, url: string) =>
    fetch(`/api/library?source=${id}&url=${encodeURIComponent(url)}`, { method: 'POST' }),
  removeLibrary: (id: string, url: string) =>
    fetch(`/api/library?source=${id}&url=${encodeURIComponent(url)}`, { method: 'DELETE' }),
  setRead: (id: string, manga: string, chapter: string, read: boolean) =>
    fetch(`/api/read?source=${id}&manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(chapter)}&read=${read}`, { method: 'POST' }),
  setBookmark: (id: string, manga: string, chapter: string, on: boolean) =>
    fetch(`/api/bookmarks?source=${id}&manga=${encodeURIComponent(manga)}&chapter=${encodeURIComponent(chapter)}&on=${on}`, { method: 'POST' }),
}

export const STATUS_LABELS: Record<number, string> = {
  0: '', 1: 'Ongoing', 2: 'Completed', 3: 'Licensed', 4: 'Publishing finished', 5: 'Cancelled', 6: 'Hiatus',
}

// Image URLs (streamed by the backend).
export const coverUrl = (sourceId: string, url?: string | null) =>
  url ? `/img/cover?source=${sourceId}&url=${encodeURIComponent(url)}` : ''

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
