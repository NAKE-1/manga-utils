import { useNavigate } from 'react-router-dom'
import { IconSearch, IconDownload, IconMenu } from './icons'

export function TopBar() {
  const nav = useNavigate()
  return (
    <header className="topbar">
      <span className="wordmark">MANGA-UTILS</span>
      <button className="iconbtn" aria-label="Downloads" onClick={() => nav('/downloads')}><IconDownload /></button>
      <button className="iconbtn" aria-label="Search" onClick={() => nav('/search')}><IconSearch /></button>
      <button className="iconbtn" aria-label="Menu"><IconMenu /></button>
    </header>
  )
}
