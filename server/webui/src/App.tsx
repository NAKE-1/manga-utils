import { Routes, Route } from 'react-router-dom'
import { TopBar } from './components/TopBar'
import { TabBar } from './components/TabBar'
import { Home } from './screens/Home'
import { ListPage } from './screens/ListPage'
import { Detail } from './screens/Detail'
import { Stub } from './screens/Stub'

export function App() {
  return (
    <div className="app">
      <TopBar />
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/list/:kind" element={<ListPage />} />
          <Route path="/library" element={<ListPage />} />
          <Route path="/search" element={<Stub name="Search" />} />
          <Route path="/settings" element={<Stub name="Settings" />} />
          <Route path="/downloads" element={<Stub name="Downloads" />} />
          <Route path="/manga/:sourceId" element={<Detail />} />
          <Route path="/reader/:sourceId" element={<Stub name="Reader" />} />
          <Route path="*" element={<Stub name="Not found" />} />
        </Routes>
      </main>
      <TabBar />
    </div>
  )
}
