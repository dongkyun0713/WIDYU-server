import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import client from '../../api/client'

interface PaymentItem {
  id: number
  memberId: number
  memberName: string
  orderName: string
  amount: number
  status: 'READY' | 'DONE' | 'PARTIAL_CANCELED' | 'CANCELED'
  paymentMethod: string
  approvedAt: string | null
  canceledAt: string | null
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
  DONE: 'bg-green-100 text-green-700',
  READY: 'bg-yellow-100 text-yellow-700',
  PARTIAL_CANCELED: 'bg-orange-100 text-orange-700',
  CANCELED: 'bg-red-100 text-red-600',
}
const STATUS_LABEL: Record<string, string> = {
  DONE: '승인',
  READY: '대기',
  PARTIAL_CANCELED: '부분 취소',
  CANCELED: '취소',
}

function toKRW(amount: number) {
  return amount.toLocaleString('ko-KR') + '원'
}

function formatDateTime(iso: string | null) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' })
}

export default function PaymentsPage() {
  const [page, setPage] = useState(0)
  const SIZE = 20

  const { data, isLoading, isError } = useQuery<PageResponse<PaymentItem>>({
    queryKey: ['admin-payments', page],
    queryFn: async () => {
      const { data } = await client.get('/admin/payments', { params: { page, size: SIZE } })
      return data.data
    },
  })

  const totalDone = data?.content
    .filter((p) => p.status === 'DONE')
    .reduce((sum, p) => sum + p.amount, 0) ?? 0

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">결제 관리</h1>
        {data && !isError && (
          <span className="text-sm text-gray-400">총 {data.totalElements.toLocaleString()}건</span>
        )}
      </div>

      {data && !isError && data.content.length > 0 && (
        <div className="mb-4 px-4 py-3 bg-indigo-50 rounded-lg text-sm text-indigo-700">
          현재 페이지 승인 합계: <span className="font-semibold">{toKRW(totalDone)}</span>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-500 uppercase text-xs tracking-wide">
            <tr>
              <th className="px-4 py-3 text-left">ID</th>
              <th className="px-4 py-3 text-left">회원</th>
              <th className="px-4 py-3 text-left">주문명</th>
              <th className="px-4 py-3 text-right">금액</th>
              <th className="px-4 py-3 text-left">결제수단</th>
              <th className="px-4 py-3 text-left">상태</th>
              <th className="px-4 py-3 text-left">승인일시</th>
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
                <td colSpan={7} className="text-center py-12 text-gray-400">결제 내역 없음</td>
              </tr>
            ) : (
              data?.content.map((p) => (
                <tr key={p.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-500">{p.id}</td>
                  <td className="px-4 py-3">
                    <span className="font-medium text-gray-800">{p.memberName}</span>
                    <span className="text-gray-400 text-xs ml-1">#{p.memberId}</span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{p.orderName}</td>
                  <td className="px-4 py-3 text-right font-medium text-gray-800">
                    {toKRW(p.amount)}
                  </td>
                  <td className="px-4 py-3 text-gray-600">{p.paymentMethod}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[p.status]}`}>
                      {STATUS_LABEL[p.status] ?? p.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {p.status === 'CANCELED' || p.status === 'PARTIAL_CANCELED'
                      ? <span className="text-red-400">{formatDateTime(p.canceledAt)} 취소</span>
                      : formatDateTime(p.approvedAt)
                    }
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
