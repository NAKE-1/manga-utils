import { useSearchParams, useNavigate } from 'react-router-dom'
import { WebviewModal } from '../components/WebviewModal'

// Standalone route wrapper around WebviewModal, for direct testing (/webview?url=… or ?source=…).
// Defaults to mangafire.to when neither is given.
export default function Webview() {
  const [params] = useSearchParams()
  const nav = useNavigate()
  const source = params.get('source') || undefined
  const url = params.get('url') || (source ? undefined : 'https://mangafire.to/')
  return <WebviewModal url={url} source={source} onClose={() => nav(-1)} />
}
