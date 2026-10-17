<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import AuthShell from '@/components/AuthShell.vue'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', password: '', nickname: '', phone: '', email: '' })

async function submit() {
  if (loading.value) return
  errorMessage.value = ''
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
    errorMessage.value = error.message || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthShell register>
    <header class="auth-card-header">
      <h1>创建账号</h1>
    </header>
    <form class="auth-form" @submit.prevent="submit">
      <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
      <div class="form-field">
        <label for="register-username">用户名</label>
        <input id="register-username" v-model="form.username" autocomplete="username" placeholder="4–32 位字母或数字" required />
      </div>
      <div class="form-field">
        <label for="register-password">密码</label>
        <input id="register-password" v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 8 位密码" minlength="8" required />
      </div>
      <div class="form-field">
        <label for="register-nickname">昵称</label>
        <input id="register-nickname" v-model="form.nickname" autocomplete="nickname" placeholder="输入你的昵称" required />
      </div>
      <div class="form-field">
        <label for="register-phone">手机号码 <span>（可选）</span></label>
        <input id="register-phone" v-model="form.phone" type="tel" autocomplete="tel" placeholder="输入手机号码" />
      </div>
      <div class="form-field">
        <label for="register-email">电子邮箱 <span>（可选）</span></label>
        <input id="register-email" v-model="form.email" type="email" autocomplete="email" placeholder="输入电子邮箱" />
      </div>
      <button type="submit" class="auth-submit" :disabled="loading" :aria-busy="loading">
        <span>{{ loading ? '正在创建账号…' : '创建账号' }}</span><span class="auth-submit-arrow" aria-hidden="true"><span>↗</span></span>
      </button>
    </form>
    <div class="auth-card-footer">
      <span>已经有账号了？</span>
      <RouterLink to="/auth/login" class="text-link">直接登录</RouterLink>
    </div>
  </AuthShell>
</template>
