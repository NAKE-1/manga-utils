import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Build straight into the server module's resources so Ktor serves it as static files.
// Dev: `npm run dev` runs a Vite server that proxies /api + /img to the Ktor backend (port 8080).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/img': 'http://localhost:8080',
    },
  },
})
