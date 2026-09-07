<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  loading.value = true
  try {
    await auth.login(form)
    ElMessage.success('登录成功，欢迎回来')
    router.replace(route.query.redirect || '/')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page-wrapper">
    <div class="auth-card">
      <div class="auth-card-header">
        <div class="auth-brand-logo">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <polygon points="3 11 22 2 13 21 11 13 3 11" />
          </svg>
        </div>
        <span class="eyebrow">WELCOME BACK</span>
        <h1>登录行迹账号</h1>
        <p>登录后管理您的出行人、跟团订单与心愿收藏。</p>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <div class="form-field">
          <label>用户名 / 账号</label>
          <input
            v-model="form.username"
            autocomplete="username"
            placeholder="请输入您的账号名称"
            required
          />
        </div>

        <div class="form-field">
          <label>密码</label>
          <input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入账号密码"
            required
          />
        </div>

        <button type="submit" class="primary-button full-width-btn" :disabled="loading">
          {{ loading ? '正在验证登录...' : '立即登录' }}
        </button>
      </form>

      <div class="auth-card-footer">
        <span>还没有行迹旅行账号？</span>
        <RouterLink to="/auth/register" class="text-link">免费注册账号 →</RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page-wrapper {
  min-height: calc(100vh - 64px);
  display: grid;
  place-items: center;
  padding: 40px 20px;
  background: radial-gradient(circle at 50% 20%, #eff6ff 0%, #f8fafc 70%);
}

.auth-card {
  width: min(440px, 100%);
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-xl);
  padding: 36px 32px;
  box-shadow: var(--shadow-lg);
}

.auth-card-header {
  text-align: center;
  margin-bottom: 28px;
}

.auth-brand-logo {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  background: linear-gradient(135deg, #0071e3, #005bb5);
  color: white;
  display: grid;
  place-items: center;
  margin: 0 auto 16px;
  box-shadow: 0 4px 12px rgba(0, 113, 227, 0.25);
}

.auth-card-header h1 {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--text-primary);
  margin: 4px 0 6px;
}

.auth-card-header p {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0;
}

.auth-form {
  display: flex;
  flex-direction: column;
}

.full-width-btn {
  width: 100%;
  min-height: 44px;
  font-size: 14px;
  font-weight: 700;
  margin-top: 8px;
}

.auth-card-footer {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--border-line);
  font-size: 13px;
  color: var(--text-secondary);
}

.text-link {
  color: var(--brand-blue);
  font-weight: 600;
}

.text-link:hover {
  text-decoration: underline;
}
</style>
