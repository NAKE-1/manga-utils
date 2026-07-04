import { useEffect, useRef, useState } from 'react'

// Pull-to-refresh for standalone/PWA mode only. When the app runs from the home screen there's no
// browser chrome, so the native pull-to-refresh is gone — this restores it: drag down from the top
// of the app shell to reload. Inert in a normal browser tab (the browser handles it there).
const THRESHOLD = 62

export function PullToRefresh() {
  const [pull, setPull] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const st = useRef({ y: 0, active: false, pull: 0 })

  useEffect(() => {
    const standalone =
      window.matchMedia('(display-mode: standalone)').matches ||
      // iOS Safari home-screen apps
      (navigator as unknown as { standalone?: boolean }).standalone === true
    if (!standalone) return
    const scroller = document.scrollingElement || document.documentElement

    const onStart = (e: TouchEvent) => {
      if (e.touches.length === 1 && scroller.scrollTop <= 0) {
        st.current.active = true; st.current.y = e.touches[0].clientY; st.current.pull = 0
      } else st.current.active = false
    }
    const onMove = (e: TouchEvent) => {
      if (!st.current.active) return
      const dy = e.touches[0].clientY - st.current.y
      if (dy > 0 && scroller.scrollTop <= 0) {
        const p = Math.min(dy * 0.5, 96) // rubber-band resistance
        st.current.pull = p; setPull(p)
      } else { st.current.active = false; st.current.pull = 0; setPull(0) }
    }
    const onEnd = () => {
      if (!st.current.active) return
      st.current.active = false
      if (st.current.pull >= THRESHOLD) { setRefreshing(true); setPull(THRESHOLD); setTimeout(() => location.reload(), 150) }
      else setPull(0)
    }

    window.addEventListener('touchstart', onStart, { passive: true })
    window.addEventListener('touchmove', onMove, { passive: true })
    window.addEventListener('touchend', onEnd, { passive: true })
    window.addEventListener('touchcancel', onEnd, { passive: true })
    return () => {
      window.removeEventListener('touchstart', onStart)
      window.removeEventListener('touchmove', onMove)
      window.removeEventListener('touchend', onEnd)
      window.removeEventListener('touchcancel', onEnd)
    }
  }, [])

  if (pull <= 0 && !refreshing) return null
  const ready = refreshing || pull >= THRESHOLD
  return (
    <div className="ptr" style={{ transform: `translateX(-50%) translateY(${pull}px)`, opacity: Math.min(1, pull / 40) }}>
      <div className={'ptr-ring' + (refreshing ? ' spin' : ready ? ' ready' : '')}>↻</div>
    </div>
  )
}
