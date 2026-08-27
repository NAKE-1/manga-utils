// Colour themes. Each is just an override of the CSS custom properties in theme.css, selected by a
// `data-theme` attribute on <html>. 'default' = the built-in Midnight violet (no attribute). The
// preview colours below drive the swatches in Settings — keep them in sync with the CSS blocks.

export type ThemeId = 'default' | 'amoled' | 'monokai' | 'dracula' | 'nord' | 'rosepine'

export interface ThemeMeta { id: ThemeId; name: string; bg: string; card: string; accent: string }

export const THEMES: ThemeMeta[] = [
  { id: 'default', name: 'Midnight', bg: '#1a1a1c', card: '#252528', accent: '#8a7cf0' },
  { id: 'amoled', name: 'AMOLED', bg: '#000000', card: '#0e0e10', accent: '#8a7cf0' },
  { id: 'monokai', name: 'Monokai Pro', bg: '#2d2a2e', card: '#353136', accent: '#ffd866' },
  { id: 'dracula', name: 'Dracula', bg: '#282a36', card: '#343746', accent: '#bd93f9' },
  { id: 'nord', name: 'Nord', bg: '#2e3440', card: '#3b4252', accent: '#88c0d0' },
  { id: 'rosepine', name: 'Rosé Pine', bg: '#191724', card: '#1f1d2e', accent: '#ebbcba' },
]

export function currentTheme(): string {
  try { return localStorage.getItem('app.theme') || 'default' } catch { return 'default' }
}

export function applyTheme(id: string) {
  const el = document.documentElement
  if (id && id !== 'default') el.setAttribute('data-theme', id)
  else el.removeAttribute('data-theme')
  try { localStorage.setItem('app.theme', id) } catch { /* private mode */ }
  // Keep the browser/PWA chrome colour in sync with the theme background.
  const bg = THEMES.find((t) => t.id === id)?.bg
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta && bg) meta.setAttribute('content', bg)
}
