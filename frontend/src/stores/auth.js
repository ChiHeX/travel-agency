import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/modules'

const TOKEN_KEY = 'travel_agency_token'
const USER_KEY = 'travel_agency_user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const cachedUser = localStorage.getItem(USER_KEY)
  const user = ref(cachedUser ? JSON.parse(cachedUser) : null)
  const isLoggedIn = computed(() => Boolean(token.value))
  const roles = computed(() => user.value?.roles || [])

  function saveSession(data) {
    token.value = data.accessToken
    user.value = data.user
    localStorage.setItem(TOKEN_KEY, data.accessToken)
    localStorage.setItem(USER_KEY, JSON.stringify(data.user))
  }

  async function login(payload) {
    const data = await authApi.login(payload)
    saveSession(data)
    return data
  }

  async function register(payload) {
    const data = await authApi.register(payload)
    saveSession(data)
    return data
  }

  async function loadUser() {
    if (!token.value) return null
    try {
      user.value = await authApi.me()
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return user.value
    } catch (error) {
      logout()
      return null
    }
  }

  function hasRole(role) {
    return roles.value.some((item) => item === role || item === `ROLE_${role}`)
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, roles, isLoggedIn, login, register, loadUser, hasRole, logout }
})
