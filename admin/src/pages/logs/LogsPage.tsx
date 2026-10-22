import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import client from '../../api/client'

type AdminAction = 'ADMIN_LOGIN' | 'MEMBER_STATUS_CHANGE' | 'FCM_TEST_SEND'

interface AuditLog {
  id: number
  adminId: number
  adminName: string
  action: AdminAction
  targetType: string | null
  targetId: number | null
  detail: string | null
  createdAt: string
}

interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

const ACTION_META: Record<AdminAction, { label: string; style: string }> = {
  ADMIN_LOGIN:          { label: '로그인',       style: 'bg-blue-100 text-blue-700' },
  MEMBER_STATUS_CHANGE: { label: '상태 변경',    style: 'bg-yellow-100 text-yellow-700' },
  FCM_TEST_SEND:        { label: 'FCM 발송',     style: 'bg-purple-100 text-purple-700' },
}

const ACTION_OPTIONS: { value: AdminAction | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'ADMIN_LOGIN', label: '로그인' },
  { value: 'MEMBER_STATUS_CHANGE', label: '상태 변경' },
  { value: 'FCM_TEST_SEND', label: 'FCM 발송' },
]

export default function LogsPage() {
  const [action, setAction] = useState<AdminAction | ''>('')
  const [page, setPage] = useState(0)
  const SIZE = 30

  const { data, isLoading, isError } = useQuery<PageResponse<AuditLog>>({
    queryKey: ['admin-audit-logs', action, page],
    queryFn: async () => {
      const { data } = await client.get('/admin/audit-logs', {
        params: { ...(action ? { action } : {}), page, size: SIZE },
      })
      return data.data
    },
  })

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">운영 로그</h1>
        {data && (
          <span className="text-sm text-gray-400">총 {data.totalElements.toLocaleString()}건</span>
        )}
      </div>

      {/* 필터 */}
      <div className="flex gap-2 mb-4">
        {ACTION_OPTIONS.map((opt) => (
          <button
            key={opt.value}
            onClick={() => { setAction(opt.value as AdminAction | ''); setPage(0) }}
            className={`px-3 py-1.5 rounded-lg text-sm border transition-colors ${
              action === opt.value
                ? 'bg-indigo-600 text-white border-indigo-600'
                : 'bg-white text-gray-600 border-gray-200 hover:border-gray-300'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-500 uppercase text-xs tracking-wide">
            <tr>
              <th className="px-4 py-3 text-left">시각</th>
              <th className="px-4 py-3 text-left">관리자</th>
              <th className="px-4 py-3 text-left">액션</th>
              <th className="px-4 py-3 text-left">대상</th>
              <th className="px-4 py-3 text-left">내용</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {isLoading ? (
              <tr>
                <td colSpan={5} className="text-center py-12 text-gray-400">불러오는 중...</td>
              </tr>
            ) : isError ? (
              <tr>
                <td colSpan={5} className="text-center py-12 text-red-400">데이터를 불러오지 못했습니다.</td>
              </tr>
            ) : data?.content.length === 0 ? (
              <tr>
                <td colSpan={5} className="text-center py-12 text-gray-400">로그가 없습니다</td>
              </tr>
            ) : (
              data?.content.map((log) => {
                const meta = ACTION_META[log.action] ?? { label: log.action, style: 'bg-gray-100 text-gray-600' }
                return (
                  <tr key={log.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-gray-500 whitespace-nowrap">
                      {new Date(log.createdAt).toLocaleString('ko-KR', {
                        month: '2-digit', day: '2-digit',
                        hour: '2-digit', minute: '2-digit', second: '2-digit',
                      })}
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-800">{log.adminName}</span>
                      <span className="text-xs text-gray-400 ml-1">#{log.adminId}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${meta.style}`}>
                        {meta.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">
                      {log.targetType && log.targetId
                        ? `${log.targetType} #${log.targetId}`
                        : '-'}
                    </td>
                    <td className="px-4 py-3 text-gray-600 text-xs max-w-xs truncate">
                      {log.detail ?? '-'}
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm text-gray-400">
            {page + 1} / {data.totalPages} 페이지
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => setPage((p) => p - 1)}
              disabled={page === 0}
              className="px-3 py-1 text-sm border rounded-md disabled:opacity-40 hover:bg-gray-50"
            >
              이전
            </button>
            <button
              onClick={() => setPage((p) => p + 1)}
              disabled={!data.hasNext}
              className="px-3 py-1 text-sm border rounded-md disabled:opacity-40 hover:bg-gray-50"
            >
              다음
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
