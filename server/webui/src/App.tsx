import { Routes, Route, useLocation } from 'react-router-dom'
import { TopBar } from './components/TopBar'
import { TabBar } from './components/TabBar'
import { Home } from './screens/Home'
import { ListPage } from './screens/ListPage'
import { Detail } from './screens/Detail'
import { Reader } from './screens/Reader'
import { Search } from './screens/Search'
import { Settings } from './screens/Settings'
import { Extensions } from './screens/Extensions'
import { Downloads } from './screens/Downloads'
import { DownloadsManager } from './screens/DownloadsManager'
import MassDownload from './screens/MassDownload'
import Stats from './screens/Stats'
import { Stub } from './screens/Stub'
import { Toasts, DownloadWatcher } from './components/Toast'
import { PullToRefresh } from './components/PullToRefresh'

export function App() {
  const loc = useLocation()
  const isReader = loc.pathname.startsWith('/reader/')
  // DownloadWatcher + Toasts are mounted ONCE here (not inside each branch) so switching in/out of the
  // full-screen reader doesn't remount them — otherwise the FlareSolverr/download watchers reset and
  // swallow the very events (a Cloudflare solve on chapter open) we want to toast.
  return (
    <>
      <DownloadWatcher />
      <Toasts />
      {isReader ? (
        <Routes>
          <Route path="/reader/:sourceId" element={<Reader />} />
        </Routes>
      ) : (
        <div className="app">
          <PullToRefresh />
          <TopBar />
          <main>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/list/:kind" element={<ListPage />} />
              <Route path="/library" element={<ListPage />} />
              <Route path="/search" element={<Search />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="/extensions" element={<Extensions />} />
              <Route path="/downloads" element={<Downloads />} />
              <Route path="/downloads/manage" element={<DownloadsManager />} />
              <Route path="/mass-download" element={<MassDownload />} />
              <Route path="/stats" element={<Stats />} />
              <Route path="/manga/:sourceId" element={<Detail />} />
              <Route path="*" element={<Stub name="Not found" />} />
            </Routes>
          </main>
          <TabBar />
        </div>
      )}
    </>
  )
}
