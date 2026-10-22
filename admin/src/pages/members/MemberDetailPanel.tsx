import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import client from '../../api/client'

interface FamilyInfo {
  familyCode: string
  inviteCode?: string
  address?: string
  points?: number
  isLeader?: boolean
  isRepresentative?: boolean
  nickname?: string
  connectedAt?: string
}

interface RecentAlbum {
  id: number
  thumbnail: string | null
  status: string
  createdAt: string
}

interface RecentPayment {
  id: number
  orderName: string
  amount: number
  status: string
  approvedAt: string | null
}

interface MemberDetail {
  id: number
  name: string
  phoneNumber: string
  type: 'SENIOR' | 'GUARDIAN'
  role: string
  status: 'ACTIVE' | 'INACTIVE' | 'DELETED'
  createdAt: string
  familyInfo: FamilyInfo | null
  activeFcmTokens: number
  recentAlbums: RecentAlbum[]
  recentPayments: RecentPayment[]
  heartEmergencyCount: number
}

const STATUS_STYLE: Record<string, string> = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-gray-100 text-gray-500',
  DELETED: 'bg-red-100 text-red-600',
  DONE: 'bg-green-100 text-green-700',
  READY: 'bg-yellow-100 text-yellow-700',
  CANCELED: 'bg-red-100 text-red-500',
  PROCESSING: 'bg-yellow-100 text-yellow-700',
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start py-2 border-b border-gray-50 last:border-0">
      <span className="text-xs text-gray-400 w-28 shrink-0 pt-0.5">{label}</span>
      <span className="text-sm text-gray-800">{value ?? '-'}</span>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-5">
      <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">{title}</p>
      <div className="bg-gray-50 rounded-lg px-3">{children}</div>
    </div>
  )
}

interface Props {
  memberId: number | null
  onClose: () => void
}

export default function MemberDetailPanel({ memberId, onClose }: Props) {
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery<MemberDetail>({
    queryKey: ['admin-member-detail', memberId],
    queryFn: async () => {
      const { data } = await client.get(`/admin/members/${memberId}`)
      return data.data
    },
    enabled: memberId !== null,
  })

  const { mutate: changeStatus, isPending, error: statusError } = useMutation({
    mutationFn: async (status: 'ACTIVE' | 'INACTIVE') => {
      await client.patch(`/admin/members/${memberId}/status`, null, { params: { status } })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-member-detail', memberId] })
      queryClient.invalidateQueries({ queryKey: ['admin-members-list'] })
    },
  })

  if (memberId === null) return null

  const handleDeactivate = () => {
    if (!data) return
    const confirmed = window.confirm(
      `${data.name}(ID ${data.id}) 계정을 비활성화할까요?\n\n` +
        '비활성화 이후 이 회원의 앱 API 요청과 새 실시간 연결이 차단됩니다. ' +
        '이미 연결된 실시간(WebSocket) 연결은 바로 끊기지 않습니다.\n' +
        '하단의 "계정 복구"로 되돌릴 수 있습니다.'
    )
    if (confirmed) changeStatus('INACTIVE')
  }

  let statusErrorMessage = ''
  if (statusError) {
    statusErrorMessage = '상태 변경에 실패했습니다. 잠시 후 다시 시도해주세요.'
    if (axios.isAxiosError(statusError) && statusError.response?.data?.message) {
      statusErrorMessage = statusError.response.data.message
    }
  }

  return (
    <>
      {/* 배경 오버레이 */}
      <div
        className="fixed inset-0 bg-black/20 z-40"
        onClick={onClose}
      />

      {/* 슬라이드 패널 */}
      <div className="fixed top-12 right-0 bottom-0 w-96 bg-white shadow-2xl z-50 flex flex-col overflow-hidden">
        {/* 헤더 */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
          <div>
            <p className="font-semibold text-gray-800">{data?.name ?? '...'}</p>
            <p className="text-xs text-gray-400">ID {memberId}</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-lg leading-none"
          >
            ✕
          </button>
        </div>

        {/* 본문 */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {isLoading ? (
            <div className="flex items-center justify-center h-40 text-gray-400 text-sm">불러오는 중...</div>
          ) : !data ? (
            <div className="flex items-center justify-center h-40 text-red-400 text-sm">불러오기 실패</div>
          ) : (
            <>
              {/* 기본 정보 */}
              <Section title="기본 정보">
                <Row label="이름" value={data.name} />
                <Row label="전화번호" value={data.phoneNumber} />
                <Row label="타입" value={data.type === 'SENIOR' ? '시니어' : '보호자'} />
                <Row label="역할" value={data.role} />
                <Row
                  label="상태"
                  value={
                    <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[data.status]}`}>
                      {data.status}
                    </span>
                  }
                />
                <Row label="가입일" value={new Date(data.createdAt).toLocaleDateString('ko-KR')} />
              </Section>

              {/* 가족 정보 */}
              {data.familyInfo && (
                <Section title="가족 정보">
                  <Row label="가족 코드" value={<code className="text-xs bg-gray-200 px-1 rounded">{data.familyInfo.familyCode}</code>} />
                  {data.type === 'SENIOR' ? (
                    <>
                      <Row label="초대 코드" value={<code className="text-xs bg-gray-200 px-1 rounded">{data.familyInfo.inviteCode}</code>} />
                      <Row label="주소" value={data.familyInfo.address} />
                      <Row label="포인트" value={`${data.familyInfo.points?.toLocaleString()}P`} />
                    </>
                  ) : (
                    <>
                      <Row label="방장" value={data.familyInfo.isLeader ? '✓' : '-'} />
                      <Row label="대표 비상연락처" value={data.familyInfo.isRepresentative ? '✓' : '-'} />
                      <Row label="닉네임" value={data.familyInfo.nickname} />
                      <Row
                        label="연결일"
                        value={data.familyInfo.connectedAt ? new Date(data.familyInfo.connectedAt).toLocaleDateString('ko-KR') : '-'}
                      />
                    </>
                  )}
                </Section>
              )}

              {/* FCM */}
              <Section title="FCM">
                <Row label="활성 토큰" value={`${data.activeFcmTokens}개`} />
                <Row label="심박 응급" value={`${data.heartEmergencyCount}건`} />
              </Section>

              {/* 최근 앨범 */}
              {data.recentAlbums.length > 0 && (
                <div className="mb-5">
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">최근 앨범</p>
                  <div className="flex gap-2">
                    {data.recentAlbums.map((a) => (
                      <div key={a.id} className="relative">
                        {a.thumbnail ? (
                          <img src={a.thumbnail} alt="" className="w-20 h-20 rounded-lg object-cover bg-gray-100" />
                        ) : (
                          <div className="w-20 h-20 rounded-lg bg-gray-100 flex items-center justify-center text-xs text-gray-400">없음</div>
                        )}
                        {a.status !== 'ACTIVE' && (
                          <span className={`absolute bottom-1 left-1 px-1 rounded text-xs font-medium ${STATUS_STYLE[a.status]}`}>
                            {a.status}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 최근 결제 */}
              {data.recentPayments.length > 0 && (
                <div className="mb-5">
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">최근 결제</p>
                  <div className="bg-gray-50 rounded-lg divide-y divide-gray-100">
                    {data.recentPayments.map((p) => (
                      <div key={p.id} className="flex items-center justify-between px-3 py-2">
                        <div>
                          <p className="text-sm text-gray-700">{p.orderName}</p>
                          <p className="text-xs text-gray-400">{p.approvedAt ? new Date(p.approvedAt).toLocaleDateString('ko-KR') : '-'}</p>
                        </div>
                        <div className="text-right">
                          <p className="text-sm font-medium">{p.amount.toLocaleString()}원</p>
                          <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${STATUS_STYLE[p.status]}`}>{p.status}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        {/* 하단 액션 */}
        {data && data.role !== 'ADMIN' && (
          <div className="px-5 py-4 border-t border-gray-100">
            {data.status === 'ACTIVE' ? (
              <button
                onClick={handleDeactivate}
                disabled={isPending}
                className="w-full py-2 rounded-lg text-sm font-medium border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-40 transition-colors"
              >
                {isPending ? '처리 중...' : '계정 비활성화'}
              </button>
            ) : data.status === 'INACTIVE' ? (
              <button
                onClick={() => changeStatus('ACTIVE')}
                disabled={isPending}
                className="w-full py-2 rounded-lg text-sm font-medium border border-green-200 text-green-700 hover:bg-green-50 disabled:opacity-40 transition-colors"
              >
                {isPending ? '처리 중...' : '계정 복구'}
              </button>
            ) : null}
            {statusErrorMessage && <p className="mt-2 text-xs text-red-500">{statusErrorMessage}</p>}
          </div>
        )}
      </div>
    </>
  )
}
