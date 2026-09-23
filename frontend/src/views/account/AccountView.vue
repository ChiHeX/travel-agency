<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'
import RequestState from '@/components/RequestState.vue'

const auth = useAuthStore()
const loading = ref(false)
const fetching = ref(true)
const error = ref('')
const submitError = ref('')
const form = reactive({ nickname: '', realName: '', phone: '', email: '', avatarUrl: '' })

async function load() {
  fetching.value = true
  error.value = ''
  try {
  const user = await authApi.profile()
  auth.user = user
  Object.assign(form, {
    nickname: user.nickname || '',
    realName: user.realName || '',
    phone: user.phone || '',
    email: user.email || '',
    avatarUrl: user.avatarUrl || ''
  })
  } catch (cause) { error.value = cause.message || '资料加载失败' }
  finally { fetching.value = false }
}
onMounted(load)

async function submit() {
  if (loading.value) return
  submitError.value = ''
  if (!form.nickname.trim() || form.nickname.length > 32) { submitError.value = '昵称应为 1–32 个字符'; return }
  if (form.phone && !/^1[3-9]\d{9}$/.test(form.phone)) { submitError.value = '请输入有效手机号'; return }
  loading.value = true
  try {
    const user = await authApi.updateProfile({
      nickname: form.nickname,
      realName: form.realName || null,
      phone: form.phone || null,
      email: form.email || null,
      avatarUrl: form.avatarUrl || null
    })
    auth.user = user
    localStorage.setItem('travel_agency_user', JSON.stringify(user))
    ElMessage.success('个人资料已成功更新')
  } catch (cause) {
    submitError.value = cause.message || '资料保存失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="account-page account-settings-page">
    <main class="account-content">
      <header class="account-page-heading">
        <div>
          <h1>个人资料</h1>
          <p>管理显示名称和联系信息。</p>
        </div>
      </header>

      <RequestState :loading="fetching" :error="error" @retry="load">
        <section aria-labelledby="identity-heading">
          <div class="settings-heading"><h2 id="identity-heading">当前账号</h2></div>
          <div class="identity-panel">
            <div class="profile-avatar" aria-hidden="true">
              {{ (auth.user?.nickname || auth.user?.username || 'U').slice(0, 1).toUpperCase() }}
            </div>
            <div class="identity-copy">
              <strong>{{ auth.user?.nickname || auth.user?.username }}</strong>
              <span class="identity-username">@{{ auth.user?.username }}</span>
            </div>
          </div>
        </section>

        <form class="profile-form" @submit.prevent="submit">
          <section class="settings-section" aria-labelledby="public-info-heading">
            <div class="settings-heading">
              <h2 id="public-info-heading">基本信息</h2>
              <p>用于展示和识别你的账号。</p>
            </div>

            <div class="settings-fields">
              <div class="setting-row">
                <div class="setting-label"><label for="profile-username">登录用户名</label></div>
                <div class="setting-control">
                  <input id="profile-username" :value="auth.user?.username" disabled autocomplete="username" />
                  <p>用于登录，当前不可修改。</p>
                </div>
              </div>
              <div class="setting-row">
                <div class="setting-label"><label for="profile-nickname">显示昵称</label></div>
                <div class="setting-control">
                  <input id="profile-nickname" v-model="form.nickname" maxlength="32" autocomplete="nickname" placeholder="输入显示昵称" required />
                  <p>展示在个人中心等位置。</p>
                </div>
              </div>
              <div class="setting-row">
                <div class="setting-label"><label for="profile-real-name">真实姓名</label></div>
                <div class="setting-control"><input id="profile-real-name" v-model="form.realName" autocomplete="name" placeholder="输入真实姓名" /></div>
              </div>
            </div>
          </section>

          <section class="settings-section" aria-labelledby="contact-info-heading">
            <div class="settings-heading">
              <h2 id="contact-info-heading">联系方式</h2>
              <p>留存常用的手机号与电子邮箱。</p>
            </div>

            <div class="settings-fields">
              <div class="setting-row">
                <div class="setting-label"><label for="profile-phone">手机号</label></div>
                <div class="setting-control"><input id="profile-phone" v-model="form.phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="输入手机号" /></div>
              </div>
              <div class="setting-row">
                <div class="setting-label"><label for="profile-email">电子邮箱</label></div>
                <div class="setting-control"><input id="profile-email" v-model="form.email" type="email" autocomplete="email" placeholder="输入电子邮箱" /></div>
              </div>
              <div class="settings-actions">
                <p v-if="submitError" class="form-error" role="alert">{{ submitError }}</p>
                <button type="submit" class="save-button" :disabled="loading">
                  {{ loading ? '正在保存...' : '保存更改' }}
                </button>
              </div>
            </div>
          </section>
        </form>
      </RequestState>
    </main>
  </div>
</template>

<style scoped>
.identity-panel {
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 96px;
  padding: 16px;
  border: 1px solid #dedee3;
  border-radius: var(--account-radius-surface);
  background: #fff;
}

.profile-avatar {
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--theme-blue);
  color: #fff;
  font-size: 24px;
  font-weight: 700;
}

.identity-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.identity-copy strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-username {
  overflow: hidden;
  color: #626269;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-form {
  margin-top: 0;
}

.save-button {
  min-height: 34px;
  padding: 0 16px;
  border: 0;
  border-radius: var(--account-radius-action);
  background: var(--theme-blue);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.save-button:hover:not(:disabled) {
  background: var(--theme-blue-hover);
}

.save-button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .identity-panel {
    padding: 14px;
  }

  .save-button {
    width: 100%;
  }
}
</style>
