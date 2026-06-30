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
import { Stub } from './screens/Stub'

export function App() {
  const loc = useLocation()
  // The reader is full-screen — no top bar / tab bar.
  if (loc.pathname.startsWith('/reader/')) {
    return (
      <Routes>
        <Route path="/reader/:sourceId" element={<Reader />} />
      </Routes>
    )
  }
  return (
    <div className="app">
      <TopBar />
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/list/:kind" element={<ListPage />} />
          <Route path="/library" element={<ListPage />} />
          <Route path="/search" element={<Search />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/extensions" element={<Extensions />} />
          <Route path="/downloads" element={<Stub name="Downloads" />} />
          <Route path="/manga/:sourceId" element={<Detail />} />
          <Route path="*" element={<Stub name="Not found" />} />
        </Routes>
      </main>
      <TabBar />
    </div>
  )
}
