import { NavLink } from 'react-router-dom'
import { IconHome, IconSearch, IconBookmark, IconSettings } from './icons'

const tabs = [
  { to: '/', label: 'Home', Icon: IconHome, end: true },
  { to: '/search', label: 'Search', Icon: IconSearch },
  { to: '/library', label: 'Library', Icon: IconBookmark },
  { to: '/settings', label: 'Settings', Icon: IconSettings },
]

export function TabBar() {
  return (
    <nav className="tabbar">
      {tabs.map(({ to, label, Icon, end }) => (
        <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? 'active' : '')}>
          <Icon />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  )
}
