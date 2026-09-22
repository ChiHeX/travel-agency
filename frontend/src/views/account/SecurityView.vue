<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const submitting = ref(false)
const error = ref('')
// 后端（BCrypt）的口令上限是 72 个 UTF-8 字节，不是 72 个字符：25 个汉字只有 25 个字符却占
// 75 字节，服务端会以 422 拒绝。这里按同一口径先拦一次，避免让用户白等一次请求。
const PASSWORD_MAX_BYTES = 72
const utf8Bytes = (value) => new TextEncoder().encode(value).length
const passwordTooLong = (value) => value.length > 72 || utf8Bytes(value) > PASSWORD_MAX_BYTES
const strength = computed(() => {
  const value = form.newPassword
  if (!value) return ''
  const groups = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((rule) => rule.test(value)).length
  return value.length < 8 ? '不足 8 位' : value.length >= 12 && groups >= 3 ? '强' : groups >= 2 ? '中' : '弱'
})

async function submit() {
  if (submitting.value) return
  error.value = ''
  if ([form.currentPassword, form.newPassword].some((value) => value.length < 8)) {
    error.value = '原密码和新密码长度应为 8–72 位'
    return
  }
  if ([form.currentPassword, form.newPassword].some(passwordTooLong)) {
    error.value = '密码过长：不超过 72 个字符且不超过 72 字节（中文、emoji 每个字符约占 3–4 字节）'
    return
  }
  if (form.newPassword !== form.confirmation) { error.value = '两次输入的新密码不一致'; return }
  if (form.newPassword === form.currentPassword) { error.value = '新密码不能与原密码相同'; return }
  submitting.value = true
  try {
    await authApi.changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword })
    Object.assign(form, { currentPassword: '', newPassword: '', confirmation: '' })
    auth.logout()
    ElMessage.success('密码修改成功，请重新登录')
    await router.replace({ name: 'login', query: { redirect: '/account/profile' } })
  } catch (cause) {
    error.value = cause.message || '修改失败，请重试'
  } finally { submitting.value = false }
}
</script>

<template>
  <div class="container narrow-container page-section">
    <div class="section-head"><div><h2>账号安全</h2><p>修改密码后，请使用新密码重新登录。</p></div></div>
    <form class="admin-panel security-form" @submit.prevent="submit">
      <fieldset :disabled="submitting">
        <div class="form-field"><label for="current-password">原密码</label><input id="current-password" v-model="form.currentPassword" type="password" autocomplete="current-password" minlength="8" maxlength="72" required /></div>
        <div class="form-field"><label for="new-password">新密码</label><input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /><small>8–72 位，建议组合大小写字母、数字与符号；中文、emoji 每个字符约占 3–4 字节，整体不超过 72 字节。</small><span v-if="strength">密码强度：{{ strength }}</span></div>
        <div class="form-field"><label for="confirm-password">确认新密码</label><input id="confirm-password" v-model="form.confirmation" type="password" autocomplete="new-password" maxlength="72" required /></div>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '正在修改…' : '保存新密码' }}</button>
      </fieldset>
    </form>
  </div>
</template>

<style scoped>
.security-form { max-width: 560px; }
fieldset { border: 0; min-width: 0; }
small { color: var(--text-secondary); }
.form-error { color: var(--status-red); margin: 12px 0; }
</style>
