<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import AuthShell from '@/components/AuthShell.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', password: '' })

async function submit() {
  if (loading.value) return
  errorMessage.value = ''
  loading.value = true
  try {
    await auth.login(form)
    ElMessage.success('登录成功，欢迎回来')
    const target = route.query.redirect
    await router.replace(typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') ? target : '/')
  } catch (error) {
    errorMessage.value = error.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthShell>
    <header class="auth-card-header">
      <h1>登录</h1>
    </header>
    <form class="auth-form" @submit.prevent="submit">
      <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
      <div class="form-field">
        <label for="login-username">用户名</label>
        <input id="login-username" v-model="form.username" autocomplete="username" placeholder="输入你的用户名" required />
      </div>
      <div class="form-field">
        <label for="login-password">密码</label>
        <input id="login-password" v-model="form.password" type="password" autocomplete="current-password" placeholder="输入你的密码" required />
      </div>
      <button type="submit" class="auth-submit" :disabled="loading" :aria-busy="loading">
        <span>{{ loading ? '正在登录…' : '登录' }}</span><span class="auth-submit-arrow" aria-hidden="true"><span>↗</span></span>
      </button>
    </form>
    <div class="auth-card-footer">
      <span>第一次来行迹？</span>
      <RouterLink to="/auth/register" class="text-link">创建一个账号</RouterLink>
    </div>
  </AuthShell>
</template>
