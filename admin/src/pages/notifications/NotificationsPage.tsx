import { useQuery } from '@tanstack/react-query'
import client from '../../api/client'

interface InactiveTokenEntry {
  memberId: number
  memberName: string
  expiredAt: string | null
}

interface RecentTestSend {
  adminName: string
  targetMemberId: number | null
  detail: string | null
  sentAt: string
}

interface FcmStats {
  activeTokenCount: number
  inactiveTokenCount: number
  membersWithToken: number
  membersWithoutToken: number
  recentlyDeactivated: InactiveTokenEntry[]
  recentTestSends: RecentTestSend[]
}

function KpiCard({ label, value, sub, color }: {
  label: string
  value: number
  sub?: string
  color?: string
}) {
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5 shadow-sm">
      <p className="text-xs text-gray-500 mb-1">{label}</p>
      <p className={`text-3xl font-bold ${color ?? 'text-gray-800'}`}>
        {value.toLocaleString()}
      </p>
      {sub && <p className="text-xs text-gray-400 mt-1">{sub}</p>}
    </div>
  )
}

export default function NotificationsPage() {
  const { data, isLoading } = useQuery<FcmStats>({
    queryKey: ['admin-fcm-stats'],
    queryFn: async () => {
      const { data } = await client.get('/admin/fcm/stats')
      return data.data
    },
    refetchInterval: 30_000,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-400">불러오는 중...</div>
    )
  }

  if (!data) return null

  const totalTokens = data.activeTokenCount + data.inactiveTokenCount
  const activeRate = totalTokens > 0
    ? Math.round((data.activeTokenCount / totalTokens) * 100)
    : 0

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">FCM 상태 보드</h1>
        <span className="text-xs text-gray-400">30초마다 자동 갱신</span>
      </div>

      {/* KPI */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <KpiCard
          label="활성 토큰"
          value={data.activeTokenCount}
          sub={`전체 중 ${activeRate}%`}
          color="text-green-600"
        />
        <KpiCard
          label="비활성 토큰"
          value={data.inactiveTokenCount}
          sub="로그아웃·만료"
          color={data.inactiveTokenCount > 50 ? 'text-red-500' : 'text-gray-800'}
        />
        <KpiCard
          label="토큰 등록 회원"
          value={data.membersWithToken}
          sub="활성 토큰 보유"
        />
        <KpiCard
          label="토큰 없는 회원"
          value={data.membersWithoutToken}
          sub="알림 수신 불가"
          color={data.membersWithoutToken > 0 ? 'text-yellow-600' : 'text-gray-800'}
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 최근 비활성화된 토큰 */}
        <div>
          <h2 className="text-sm font-semibold text-gray-700 mb-3">최근 비활성화 토큰</h2>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
            {data.recentlyDeactivated.length === 0 ? (
              <div className="py-10 text-center text-sm text-gray-400">없음</div>
            ) : (
              <table className="w-full text-sm">
                <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
                  <tr>
                    <th className="px-4 py-2 text-left">회원</th>
                    <th className="px-4 py-2 text-left">비활성화</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {data.recentlyDeactivated.map((t, i) => (
                    <tr key={i} className="hover:bg-gray-50">
                      <td className="px-4 py-2">
                        <span className="font-medium text-gray-800">{t.memberName}</span>
                        <span className="text-xs text-gray-400 ml-1">#{t.memberId}</span>
                      </td>
                      <td className="px-4 py-2 text-gray-400 text-xs whitespace-nowrap">
                        {t.expiredAt
                          ? new Date(t.expiredAt).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
                          : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* 최근 테스트 발송 */}
        <div>
          <h2 className="text-sm font-semibold text-gray-700 mb-3">최근 테스트 발송</h2>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
            {data.recentTestSends.length === 0 ? (
              <div className="py-10 text-center text-sm text-gray-400">발송 기록 없음</div>
            ) : (
              <table className="w-full text-sm">
                <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
                  <tr>
                    <th className="px-4 py-2 text-left">관리자</th>
                    <th className="px-4 py-2 text-left">내용</th>
                    <th className="px-4 py-2 text-left">시각</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {data.recentTestSends.map((s, i) => (
                    <tr key={i} className="hover:bg-gray-50">
                      <td className="px-4 py-2 font-medium text-gray-700">{s.adminName}</td>
                      <td className="px-4 py-2 text-gray-500 text-xs max-w-[180px] truncate">
                        {s.detail ?? '-'}
                      </td>
                      <td className="px-4 py-2 text-gray-400 text-xs whitespace-nowrap">
                        {new Date(s.sentAt).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
