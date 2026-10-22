import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import client from '../../api/client'
import MemberDetailPanel from './MemberDetailPanel'

interface MemberDetail {
  id: number
  name: string
  phoneNumber: string
  type: 'SENIOR' | 'GUARDIAN'
  role: 'ADMIN' | 'USER' | 'TEMPORARY'
  status: 'ACTIVE' | 'INACTIVE' | 'DELETED'
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

const TYPE_LABEL: Record<string, string> = { SENIOR: '시니어', GUARDIAN: '보호자' }
const ROLE_LABEL: Record<string, string> = { ADMIN: '관리자', USER: '일반', TEMPORARY: '임시' }
const STATUS_STYLE: Record<string, string> = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-gray-100 text-gray-600',
  DELETED: 'bg-red-100 text-red-600',
}

export default function MembersPage() {
  const [search, setSearch] = useState('')
  const [inputValue, setInputValue] = useState('')
  const [page, setPage] = useState(0)
  const [selectedMemberId, setSelectedMemberId] = useState<number | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const SIZE = 20

  useEffect(() => {
    const idParam = searchParams.get('memberId')
    if (idParam) {
      setSelectedMemberId(Number(idParam))
      setSearchParams({}, { replace: true })
    }
  }, [])

  const { data, isLoading, isError } = useQuery<PageResponse<MemberDetail>>({
    queryKey: ['admin-members-list', search, page],
    queryFn: async () => {
      const { data } = await client.get('/admin/members/list', {
        params: { ...(search ? { name: search } : {}), page, size: SIZE },
      })
      return data.data
    },
  })

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    setSearch(inputValue)
    setPage(0)
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">회원 관리</h1>
        {data && !isError && (
          <span className="text-sm text-gray-400">총 {data.totalElements.toLocaleString()}명</span>
        )}
      </div>

      <form onSubmit={handleSearch} className="flex gap-2 mb-4">
        <input
          type="text"
          placeholder="이름 검색..."
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          className="border border-gray-300 rounded-md px-3 py-2 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <button
          type="submit"
          className="px-4 py-2 bg-indigo-600 text-white text-sm rounded-md hover:bg-indigo-700"
        >
          검색
        </button>
        {search && (
          <button
            type="button"
            onClick={() => { setSearch(''); setInputValue(''); setPage(0) }}
            className="px-4 py-2 bg-gray-100 text-gray-600 text-sm rounded-md hover:bg-gray-200"
          >
            초기화
          </button>
        )}
      </form>

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-500 uppercase text-xs tracking-wide">
            <tr>
              <th className="px-4 py-3 text-left">ID</th>
              <th className="px-4 py-3 text-left">이름</th>
              <th className="px-4 py-3 text-left">전화번호</th>
              <th className="px-4 py-3 text-left">타입</th>
              <th className="px-4 py-3 text-left">역할</th>
              <th className="px-4 py-3 text-left">상태</th>
              <th className="px-4 py-3 text-left">가입일</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {isLoading ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-gray-400">불러오는 중...</td>
              </tr>
            ) : isError ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-red-400">데이터를 불러오지 못했습니다.</td>
              </tr>
            ) : data?.content.length === 0 ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-gray-400">결과 없음</td>
              </tr>
            ) : (
              data?.content.map((m) => (
                <tr
                  key={m.id}
                  className="hover:bg-indigo-50 cursor-pointer"
                  onClick={() => setSelectedMemberId(m.id)}
                >
                  <td className="px-4 py-3 text-gray-500">{m.id}</td>
                  <td className="px-4 py-3 font-medium text-gray-800">{m.name}</td>
                  <td className="px-4 py-3 text-gray-600">{m.phoneNumber ?? '-'}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${m.type === 'SENIOR' ? 'bg-blue-100 text-blue-700' : 'bg-purple-100 text-purple-700'}`}>
                      {TYPE_LABEL[m.type]}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-600">{ROLE_LABEL[m.role] ?? m.role}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[m.status]}`}>
                      {m.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(m.createdAt).toLocaleDateString('ko-KR')}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <MemberDetailPanel
        key={selectedMemberId}
        memberId={selectedMemberId}
        onClose={() => setSelectedMemberId(null)}
      />

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
