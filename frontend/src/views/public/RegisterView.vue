<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '', phone: '', email: '' })

async function submit() {
  loading.value = true
  try {
    await auth.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname,
      ...(form.phone ? { phone: form.phone } : {}),
      ...(form.email ? { email: form.email } : {})
    })
    ElMessage.success('注册成功，欢迎加入行迹旅行')
    router.replace('/')
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
        <span class="eyebrow">START YOUR JOURNEY</span>
        <h1>创建行迹账号</h1>
        <p>开启品质跟团之旅，出行人信息安全加密存储。</p>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <div class="form-field">
          <label>用户名 / 账号名</label>
          <input
            v-model="form.username"
            autocomplete="username"
            placeholder="4-32 位字母或数字"
            required
          />
        </div>

        <div class="form-field">
          <label>登录密码</label>
          <input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            placeholder="至少 8 位安全密码"
            minlength="8"
            required
          />
        </div>

        <div class="form-field">
          <label>您的称呼 / 昵称</label>
          <input
            v-model="form.nickname"
            placeholder="在平台展示的个性称呼"
            required
          />
        </div>

        <div class="form-field">
          <label>手机号码（用于订单通知）</label>
          <input
            v-model="form.phone"
            inputmode="tel"
            placeholder="接收出团通知短信"
          />
        </div>

        <div class="form-field">
          <label>电子邮箱（可选）</label>
          <input
            v-model="form.email"
            type="email"
            placeholder="接收电子行程确认单"
          />
        </div>

        <button type="submit" class="primary-button full-width-btn" :disabled="loading">
          {{ loading ? '正在创建账号...' : '立即注册账号' }}
        </button>
      </form>

      <div class="auth-card-footer">
        <span>已有行迹账号？</span>
        <RouterLink to="/auth/login" class="text-link">返回登录 →</RouterLink>
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
  width: min(460px, 100%);
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-xl);
  padding: 36px 32px;
  box-shadow: var(--shadow-lg);
}

.auth-card-header {
  text-align: center;
  margin-bottom: 24px;
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
