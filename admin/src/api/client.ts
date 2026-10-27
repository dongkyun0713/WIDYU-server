import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  withCredentials: true, // HttpOnly 쿠키 자동 전송
})

const LOGIN_URL = '/auth/admin/login'

client.interceptors.request.use((config) => {
  // 로그인은 토큰 없이 보낸다. 만료된 토큰이 실리면 JWT 필터가 로그인 처리 전에 401을 돌려준다.
  if (config.url === LOGIN_URL) {
    config.headers.delete('Authorization')
    return config
  }
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

    // 로그인의 401은 토큰 만료가 아니라 이메일·비밀번호 오류다. 여기서 refresh를 시도하면
    // 로그인 화면이 새로고침돼 호출자가 오류를 표시하지 못한다.
    if (error.response?.status !== 401 || original._retry || original.url === LOGIN_URL) {
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
