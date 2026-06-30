// Minimal inline SVG icon set (stroke-based, currentColor).
type P = { className?: string }
const S = (d: string) => ({ className }: P) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d={d} />
  </svg>
)

export const IconHome = S('M3 11l9-8 9 8 M5 10v10a1 1 0 001 1h12a1 1 0 001-1V10')
export const IconSearch = ({ className }: P) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className={className}>
    <circle cx="11" cy="11" r="7" /><path d="M21 21l-4-4" />
  </svg>
)
export const IconBookmark = S('M6 3h12a1 1 0 011 1v17l-7-5-7 5V4a1 1 0 011-1z')
export const IconSettings = ({ className }: P) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-2.9 1.2V21a2 2 0 01-4 0v-.1A1.7 1.7 0 005.2 19l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.7 1.7 0 002.6 9H2.5a2 2 0 010-4h.1A1.7 1.7 0 005 4.2l-.1-.1a2 2 0 112.8-2.8l.1.1A1.7 1.7 0 0011 2.6V2.5a2 2 0 014 0v.1a1.7 1.7 0 002.9 1.2l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9z" />
  </svg>
)
export const IconDownload = S('M12 3v12 M7 11l5 5 5-5 M5 21h14')
export const IconMenu = S('M3 6h18 M3 12h18 M3 18h18')
export const IconChevronRight = S('M9 5l7 7-7 7')
export const IconChevronDown = S('M6 9l6 6 6-6')
export const IconCloudflare = ({ className }: P) => (
  <svg viewBox="0 0 24 24" fill="currentColor" className={className}><path d="M16.5 16.5H6.2A3.7 3.7 0 016 9.1a5.2 5.2 0 0110-1.6 3.6 3.6 0 01.5 9z" /></svg>
)
export const IconChevronLeft = S('M15 5l-7 7 7 7')
export const IconArrowUp = S('M12 20V5 M5 12l7-7 7 7')
export const IconArrowLeft = S('M15 5l-7 7 7 7 M8 12h12')
export const IconClock = ({ className }: P) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" />
  </svg>
)
export const IconBook = S('M4 4h11a3 3 0 013 3v13a2 2 0 00-2-2H4z M7 8h8 M7 12h6')
export const IconPen = S('M14 4l6 6 M3 21l1-5L16 4l4 4L8 20z')
export const IconCalendar = S('M7 3v3 M17 3v3 M4 8h16 M5 6h14a1 1 0 011 1v12a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1z')
export const IconBookOpen = S('M12 6c-2-1.5-5-1.5-8-1v13c3-.5 6-.5 8 1 2-1.5 5-1.5 8-1V5c-3-.5-6-.5-8 1z M12 6v13')
export const IconSort = S('M7 4v16 M4 8l3-4 3 4 M17 20V4 M14 16l3 4 3-4')
export const IconHeart = ({ className, filled }: P & { filled?: boolean }) => (
  <svg viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" className={className}>
    <path d="M12 21s-7-4.5-9.5-9A5 5 0 0112 6a5 5 0 019.5 6c-2.5 4.5-9.5 9-9.5 9z" />
  </svg>
)
export const IconBookmarkSm = ({ className, filled }: P & { filled?: boolean }) => (
  <svg viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" className={className}>
    <path d="M6 3h12a1 1 0 011 1v17l-7-5-7 5V4a1 1 0 011-1z" />
  </svg>
)
