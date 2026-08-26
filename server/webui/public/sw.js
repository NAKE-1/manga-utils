/*
 * App-shell service worker. Goal: cold launch / reload paints instantly instead of blank→fetch→render
 * (which on the iOS home-screen PWA is the load-window where the tab bar shifts and the page flashes).
 *
 * Strategy:
 *  - /assets/*  → cache-first. Vite hashes these filenames, so a given URL never changes → safe to cache
 *    forever; a new build = new filenames = cache miss = fetched fresh. This is the instant part.
 *  - navigations + root static (index.html, icons, manifest) → network-first with a cache fallback, so
 *    the shell is ALWAYS fresh when online (no "stuck on an old version") but still loads instantly from
 *    cache when offline / on a slow link.
 *  - /api/* and /img/* → not touched (dynamic data / images manage their own caching).
 */
const CACHE = 'mu-shell-v1'

self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/img/')) return // dynamic — pass through

  if (url.pathname.startsWith('/assets/')) {
    // Immutable hashed bundle: serve from cache, fetch+store on first miss.
    e.respondWith(
      caches.open(CACHE).then((c) =>
        c.match(req).then((hit) => hit || fetch(req).then((res) => { if (res.ok) c.put(req, res.clone()); return res })),
      ),
    )
    return
  }

  // Shell (index.html for any route, icons, manifest): fresh when online, cached fallback offline.
  e.respondWith(
    fetch(req)
      .then((res) => { if (res.ok) caches.open(CACHE).then((c) => c.put(req, res.clone())); return res })
      .catch(() => caches.match(req).then((hit) => hit || caches.match('/index.html'))),
  )
})
