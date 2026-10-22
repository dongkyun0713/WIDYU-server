import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'
import SearchModal from '../SearchModal'
import client from '../../api/client'

// dev 서버와 운영 배포가 모두 production mode로 빌드되므로 MODE 대신 배포 워크플로가 주입하는 VITE_APP_ENV로 구분한다.
// deploy-dev.yml은 dev, deploy-prod.yml은 prod를 넣고, 값이 없는 로컬 실행·CI 빌드는 LOCAL로 표시한다.
const ENV_MAP: Record<string, { label: string; style: string }> = {
  prod: { label: 'PROD', style: 'bg-red-500 text-white' },
  dev: { label: 'DEV', style: 'bg-yellow-400 text-yellow-900' },
}

function EnvBadge() {
  const env = ENV_MAP[import.meta.env.VITE_APP_ENV] ?? { label: 'LOCAL', style: 'bg-gray-400 text-white' }
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-bold tracking-wide ${env.style}`}>
      {env.label}
    </span>
  )
}

export default function Header() {
  const clearToken = useAuthStore((s) => s.clearToken)
  const navigate = useNavigate()
  const [searchOpen, setSearchOpen] = useState(false)

  const handleLogout = async () => {
    try {
      await client.post('/auth/admin/logout')
    } finally {
      clearToken()
      navigate('/login')
    }
  }

  useEffect(() => {
    if (import.meta.env.MODE === 'development') return
    const handleKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [])

  return (
    <>
      <header className="fixed top-0 left-0 right-0 h-12 bg-gray-900 border-b border-gray-700 flex items-center px-4 z-50">
        <div className="w-56 shrink-0 flex items-center gap-2">
          <span className="text-white font-bold text-sm">WIDYU Admin</span>
          <EnvBadge />
        </div>

        {/* 검색 버튼: 운영 서버에서만 표시 */}
        {import.meta.env.MODE !== 'development' && (
          <button
            onClick={() => setSearchOpen(true)}
            className="flex items-center gap-2 px-3 py-1.5 bg-gray-800 hover:bg-gray-700 border border-gray-600 rounded-lg text-gray-400 hover:text-gray-200 text-xs transition-colors"
          >
            <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <span>검색...</span>
            <kbd className="hidden sm:inline bg-gray-700 px-1 rounded text-gray-500">⌘K</kbd>
          </button>
        )}

        <div className="flex-1" />

        <div className="flex items-center gap-4">
          <span className="text-gray-400 text-xs">관리자</span>
          <button
            onClick={handleLogout}
            className="text-xs text-gray-400 hover:text-white border border-gray-600 hover:border-gray-400 px-3 py-1 rounded transition-colors"
          >
            로그아웃
          </button>
        </div>
      </header>

      {searchOpen && (
        <SearchModal
          onClose={() => setSearchOpen(false)}
          onSelectMember={(id) => navigate(`/members?memberId=${id}`)}
        />
      )}
    </>
  )
}
