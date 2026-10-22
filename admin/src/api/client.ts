import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  withCredentials: true, // HttpOnly 쿠키 자동 전송
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let isRefreshing = false
type QueueEntry = { resolve: (token: string) => void; reject: (err: unknown) => void }
let refreshQueue: QueueEntry[] = []

function flushQueue(token: string) {
  refreshQueue.forEach(({ resolve }) => resolve(token))
  refreshQueue = []
}

function rejectQueue(err: unknown) {
  refreshQueue.forEach(({ reject }) => reject(err))
  refreshQueue = []
}

client.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config

    // 관리자 인증 API(로그인·refresh·로그아웃)의 401은 토큰 만료가 아니라 인증 실패 결과다.
    // 여기서 refresh를 시도하면 로그인 화면이 새로고침돼 호출자가 오류를 표시하지 못한다.
    const isAuthRequest = original.url?.startsWith('/auth/admin/')
    if (error.response?.status !== 401 || original._retry || isAuthRequest) {
      return Promise.reject(error)
    }

    if (isRefreshing) {
      return new Promise((resolve, reject) => {
        refreshQueue.push({
          resolve: (newToken) => {
            original.headers.Authorization = `Bearer ${newToken}`
            resolve(client(original))
          },
          reject,
        })
      })
    }

    original._retry = true
    isRefreshing = true

    try {
      // refresh token은 HttpOnly 쿠키로 자동 전송됨 (withCredentials)
      const { data } = await axios.post('/api/v1/auth/admin/refresh', null, { withCredentials: true })
      const newAccessToken: string = data.accessToken

      localStorage.setItem('admin_token', newAccessToken)
      client.defaults.headers.common.Authorization = `Bearer ${newAccessToken}`
      flushQueue(newAccessToken)

      original.headers.Authorization = `Bearer ${newAccessToken}`
      return client(original)
    } catch (refreshError) {
      rejectQueue(refreshError)
      localStorage.removeItem('admin_token')
      window.location.href = import.meta.env.BASE_URL + 'login'
      return Promise.reject(refreshError)
    } finally {
      isRefreshing = false
    }
  }
)

export default client
