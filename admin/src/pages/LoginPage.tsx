import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import client from '../api/client'
import { useAuthStore } from '../store/authStore'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const setToken = useAuthStore((s) => s.setToken)
  const navigate = useNavigate()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    try {
      const { data } = await client.post('/auth/admin/login', { email, password })
      setToken(data.accessToken)
      navigate('/')
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.status !== 401) {
        // 403(관리자 아님)·429(요청 제한)·네트워크 오류는 비밀번호 오류와 구분해 보여준다.
        setError(err.response?.data?.message ?? '로그인에 실패했습니다. 잠시 후 다시 시도해주세요.')
        return
      }
      setError('이메일 또는 비밀번호가 올바르지 않습니다.')
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <form
        onSubmit={handleSubmit}
        className="bg-white p-8 rounded-xl shadow w-80 space-y-4"
      >
        <h1 className="text-2xl font-bold text-center text-gray-800">WIDYU Admin</h1>
        {error && <p className="text-sm text-red-500 text-center">{error}</p>}
        <div>
          <label className="block text-sm text-gray-600 mb-1">이메일</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            required
          />
        </div>
        <div>
          <label className="block text-sm text-gray-600 mb-1">비밀번호</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            required
          />
        </div>
        <button
          type="submit"
          className="w-full bg-indigo-600 text-white py-2 rounded-md text-sm font-medium hover:bg-indigo-700 transition-colors"
        >
          로그인
        </button>
      </form>
    </div>
  )
}
