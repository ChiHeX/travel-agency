<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'
import { codePointLength, utf8Bytes } from '@/utils/text'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const submitting = ref(false)
const error = ref('')
/**
 * 口令字段分两类，页面校验必须与后端、契约（API.md §4.2）保持同一套规则：
 *
 * ① 设置类（新密码）：**8–72 个字符 + UTF-8 不超过 72 字节**。「字符」按 Unicode 码点计
 *    （JSON Schema 的 maxLength 与后端 @CodePointLength 同口径），而 JS 的 String#length 数的是
 *    UTF-16 码元（一个 emoji 记 2）；字节上限来自 BCrypt 的硬限制 —— 25 个汉字只有 25 个字符
 *    却占 75 字节，服务端会以 422 拒绝。
 *
 * ② 验证类（原密码）：**只要非空 + UTF-8 不超过 72 字节，不套用 8 字符下限**。历史口令是按
 *    UTF-16 码元口径创建并保存的（`😀😀😀😀` 只有 4 个字符却有 8 个码元），当时能注册、现在也
 *    仍能登录；若在这里按「不足 8 个字符」拦下，这些账号就再也改不了密码 —— 而契约不提供管理员
 *    重置入口，等于把旧账号锁死在旧口令上。原密码一律交给服务端做哈希匹配。
 */
const PASSWORD_MIN_CHARS = 8
const PASSWORD_MAX_CHARS = 72
const PASSWORD_MAX_BYTES = 72
const strength = computed(() => {
  const value = form.newPassword
  if (!value) return ''
  const groups = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((rule) => rule.test(value)).length
  // 强度阈值同样按码点计，否则同一个框里的长度会出现两种口径（emoji 密码会被多算一倍）
  const length = codePointLength(value)
  return length < PASSWORD_MIN_CHARS ? '不足 8 位' : length >= 12 && groups >= 3 ? '强' : groups >= 2 ? '中' : '弱'
})

/** 设置类规则：返回不合规的原因，合规时返回空串（上限与计数器共用这些常量）。 */
function newPasswordProblem(value) {
  if (codePointLength(value) < PASSWORD_MIN_CHARS) return `新密码至少 ${PASSWORD_MIN_CHARS} 位`
  if (codePointLength(value) > PASSWORD_MAX_CHARS || utf8Bytes(value) > PASSWORD_MAX_BYTES) {
    return '新密码过长：不超过 72 个字符且不超过 72 字节（中文、emoji 每个字符约占 3–4 字节）'
  }
  return ''
}

/** 验证类规则：只拦「空」与「不可能是任何已存口令的超长输入」，长度下限交给服务端哈希匹配。 */
function currentPasswordProblem(value) {
  if (!value) return '请输入原密码'
  if (utf8Bytes(value) > PASSWORD_MAX_BYTES) return '原密码过长：最多 72 字节'
  return ''
}

async function submit() {
  if (submitting.value) return
  error.value = ''
  const currentProblem = currentPasswordProblem(form.currentPassword)
  if (currentProblem) { error.value = currentProblem; return }
  const newProblem = newPasswordProblem(form.newPassword)
  if (newProblem) { error.value = newProblem; return }
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
  <div class="account-settings-page">
    <main class="account-content">
      <header class="account-page-heading">
        <div>
          <h1>账号安全</h1>
          <p>修改密码后，请使用新密码重新登录。</p>
        </div>
      </header>
      <section class="settings-section" aria-labelledby="password-heading">
        <div class="settings-heading">
          <h2 id="password-heading">修改密码</h2>
          <p>请先验证原密码，再设置新密码。</p>
        </div>
        <form class="security-form" @submit.prevent="submit">
          <!--
            三个密码框都不设 maxlength：maxlength 数的是 UTF-16 码元，会在打字过程中把契约允许的
            口令静默截短（32 个 emoji 只留下 16 个），用户设置的密码与真正参与校验的就不一致了。
            上限改由提交时的码点/字节校验负责（PasswordChangeRequest，见 API.md §4.2）。
            只有「新密码」保留 minlength="8"（设置类规则的 8 个字符必然不短于 8 个码元）；
            「原密码」不设 minlength：它只做哈希匹配，历史口令可能不足 8 个字符（见上面的说明）。
          -->
          <fieldset :disabled="submitting">
            <div class="settings-fields">
              <div class="setting-row">
                <div class="setting-label"><label for="current-password">原密码</label></div>
                <div class="setting-control password-entry">
                  <input id="current-password" v-model="form.currentPassword" type="password" autocomplete="current-password" required />
                  <p>沿用当前口令即可：按哈希核对，不套用新密码的长度规则。</p>
                </div>
              </div>
              <div class="setting-row">
                <div class="setting-label"><label for="new-password">新密码</label></div>
                <div class="setting-control password-entry">
                  <input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" minlength="8" required />
                  <p>8–72 位，且不超过 72 个 UTF-8 字节。<span v-if="strength">密码强度：{{ strength }}</span></p>
                </div>
              </div>
              <div class="setting-row">
                <div class="setting-label"><label for="confirm-password">确认新密码</label></div>
                <div class="setting-control"><input id="confirm-password" v-model="form.confirmation" type="password" autocomplete="new-password" required /></div>
              </div>
              <div class="settings-actions">
                <p v-if="error" class="form-error" role="alert">{{ error }}</p>
                <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? '正在修改…' : '保存新密码' }}</button>
              </div>
            </div>
          </fieldset>
        </form>
      </section>
    </main>
  </div>
</template>

<style scoped>
fieldset { border: 0; min-width: 0; }
.password-entry span { margin-left: 12px; }
</style>
