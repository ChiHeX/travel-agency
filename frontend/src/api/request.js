import axios from 'axios'
import { ElMessage } from 'element-plus'

export class ApiError extends Error {
  constructor(message, options = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = options.code || 'UNKNOWN_ERROR'
    this.status = options.status || 0
    this.errors = options.errors || []
    this.traceId = options.traceId || ''
  }
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 12000,
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('travel_agency_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (response) => {
    if (response.status === 204) return undefined

    const body = response.data
    if (!body || typeof body.code !== 'string' || !Object.hasOwn(body, 'data')) {
      throw new ApiError('服务端响应不符合 API 契约', {
        code: 'INVALID_API_RESPONSE',
        status: response.status
      })
    }
    if (body.code !== 'OK') {
      throw new ApiError(body.message || '请求失败', {
        code: body.code,
        status: response.status,
        errors: body.errors,
        traceId: body.traceId
      })
    }
    return body.data
  },
  (error) => {
    if (error instanceof ApiError) return Promise.reject(error)

    const body = error.response?.data
    const apiError = new ApiError(body?.message || error.message || '网络请求失败', {
      code: body?.code || 'NETWORK_ERROR',
      status: error.response?.status,
      errors: body?.errors,
      traceId: body?.traceId
    })
    if (apiError.status !== 401) ElMessage.error(apiError.message)
    return Promise.reject(apiError)
  }
)

export default request
