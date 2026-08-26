import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import './theme.css'

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)

// App-shell service worker: caches the hashed JS/CSS so a reload / cold launch on the home-screen PWA
// paints instantly (no blank load-window where the tab bar shifts). Registered after load so it never
// competes with the first render. sw.js is served no-cache, so SW updates take on the next visit.
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => { navigator.serviceWorker.register('/sw.js').catch(() => {}) })
}
