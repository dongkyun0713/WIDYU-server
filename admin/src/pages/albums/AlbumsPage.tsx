import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import client from '../../api/client'

interface AlbumItem {
  id: number
  memberId: number
  memberName: string
  thumbnail: string | null
  contentPreview: string | null
  likeCount: number
  commentCount: number
  viewCount: number
  status: 'ACTIVE' | 'INACTIVE' | 'DELETED' | 'PROCESSING'
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

const STATUS_STYLE: Record<string, string> = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-gray-100 text-gray-600',
  DELETED: 'bg-red-100 text-red-600',
  PROCESSING: 'bg-yellow-100 text-yellow-700',
}

export default function AlbumsPage() {
  const [page, setPage] = useState(0)
  const SIZE = 20

  const { data, isLoading, isError } = useQuery<PageResponse<AlbumItem>>({
    queryKey: ['admin-albums', page],
    queryFn: async () => {
      const { data } = await client.get('/admin/albums', { params: { page, size: SIZE } })
      return data.data
    },
  })

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">앨범 관리</h1>
        {data && !isError && (
          <span className="text-sm text-gray-400">총 {data.totalElements.toLocaleString()}건</span>
        )}
      </div>

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-500 uppercase text-xs tracking-wide">
            <tr>
              <th className="px-4 py-3 text-left">ID</th>
              <th className="px-4 py-3 text-left">썸네일</th>
              <th className="px-4 py-3 text-left">작성자</th>
              <th className="px-4 py-3 text-left">내용</th>
              <th className="px-4 py-3 text-center">좋아요</th>
              <th className="px-4 py-3 text-center">댓글</th>
              <th className="px-4 py-3 text-center">조회</th>
              <th className="px-4 py-3 text-left">상태</th>
              <th className="px-4 py-3 text-left">업로드일</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {isLoading ? (
              <tr>
                <td colSpan={9} className="text-center py-12 text-gray-400">불러오는 중...</td>
              </tr>
            ) : isError ? (
              <tr>
                <td colSpan={9} className="text-center py-12 text-red-400">데이터를 불러오지 못했습니다.</td>
              </tr>
            ) : data?.content.length === 0 ? (
              <tr>
                <td colSpan={9} className="text-center py-12 text-gray-400">앨범 없음</td>
              </tr>
            ) : (
              data?.content.map((a) => (
                <tr key={a.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-500">{a.id}</td>
                  <td className="px-4 py-3">
                    {a.thumbnail ? (
                      <img
                        src={a.thumbnail}
                        alt=""
                        className="w-12 h-12 rounded object-cover bg-gray-100"
                      />
                    ) : (
                      <div className="w-12 h-12 rounded bg-gray-100 flex items-center justify-center text-gray-300 text-xs">
                        없음
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <span className="font-medium text-gray-800">{a.memberName}</span>
                    <span className="text-gray-400 text-xs ml-1">#{a.memberId}</span>
                  </td>
                  <td className="px-4 py-3 text-gray-600 max-w-xs truncate">
                    {a.contentPreview ?? <span className="text-gray-300">내용 없음</span>}
                  </td>
                  <td className="px-4 py-3 text-center text-gray-600">{a.likeCount}</td>
                  <td className="px-4 py-3 text-center text-gray-600">{a.commentCount}</td>
                  <td className="px-4 py-3 text-center text-gray-600">{a.viewCount}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[a.status]}`}>
                      {a.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(a.createdAt).toLocaleDateString('ko-KR')}
                  </td>
                </tr>
              ))
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
