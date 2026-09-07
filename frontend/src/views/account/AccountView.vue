<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ nickname: '', realName: '', phone: '', email: '', avatarUrl: '' })

onMounted(() => {
  const user = auth.user || {}
  Object.assign(form, {
    nickname: user.nickname || '',
    realName: user.realName || '',
    phone: user.phone || '',
    email: user.email || '',
    avatarUrl: user.avatarUrl || ''
  })
})

async function submit() {
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
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="account-page">
    <div class="container account-layout page-section">
      <!-- 系统 Settings Sidebar -->
      <aside class="account-sidebar-nav">
        <div class="user-profile-summary">
          <div class="profile-avatar">
            {{ (auth.user?.nickname || auth.user?.username || 'U').slice(0, 1).toUpperCase() }}
          </div>
          <div class="profile-text">
            <strong>{{ auth.user?.nickname || auth.user?.username }}</strong>
            <span class="user-role-badge">{{ auth.roles.join(' · ') }}</span>
          </div>
        </div>

        <nav class="settings-nav-list">
          <RouterLink to="/account/profile" class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" />
            </svg>
            个人资料与安全
          </RouterLink>
          <RouterLink to="/account/orders">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 0 1-8 0" />
            </svg>
            我的订单
          </RouterLink>
          <RouterLink to="/account/travelers">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" />
            </svg>
            常用出行人
          </RouterLink>
          <RouterLink to="/account/favorites">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
            </svg>
            我的心愿收藏
          </RouterLink>
          <RouterLink to="/account/messages">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            站内通知消息
          </RouterLink>
          <RouterLink to="/account/consultations">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" /><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" /><line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
            在线咨询记录
          </RouterLink>
        </nav>
      </aside>

      <!-- Main Profile Settings Panel -->
      <main class="account-content-main">
        <div class="section-head">
          <div>
            <span class="eyebrow">ACCOUNT SETTINGS</span>
            <h2>个人资料设置</h2>
            <p>管理账号基础信息、绑定手机与电子邮箱。</p>
          </div>
        </div>

        <div class="admin-panel profile-settings-card">
          <form class="profile-form-grid" @submit.prevent="submit">
            <div class="form-field">
              <label>登录用户名（不可更改）</label>
              <input :value="auth.user?.username" disabled class="disabled-input" />
            </div>

            <div class="form-field">
              <label>显示昵称</label>
              <input v-model="form.nickname" placeholder="请输入个性昵称" required />
            </div>

            <div class="form-field">
              <label>真实姓名</label>
              <input v-model="form.realName" placeholder="用于实名认证" />
            </div>

            <div class="form-field">
              <label>绑定手机号</label>
              <input v-model="form.phone" inputmode="tel" placeholder="接收重要出行通知" />
            </div>

            <div class="form-field wide">
              <label>电子邮箱</label>
              <input v-model="form.email" type="email" placeholder="接收行程确认单与电子发票" />
            </div>

            <div class="form-actions-row">
              <button type="submit" class="primary-button" :disabled="loading">
                {{ loading ? '正在保存...' : '保存个人资料' }}
              </button>
            </div>
          </form>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.account-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

.account-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 36px;
  align-items: flex-start;
}

.account-sidebar-nav {
  background: white;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-lg);
  padding: 20px 16px;
  box-shadow: var(--shadow-xs);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.user-profile-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-line);
}

.profile-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--brand-blue);
  color: white;
  font-size: 18px;
  font-weight: 700;
  display: grid;
  place-items: center;
  box-shadow: 0 2px 8px rgba(0, 113, 227, 0.25);
}

.profile-text strong {
  display: block;
  font-size: 14px;
  color: var(--text-primary);
}

.user-role-badge {
  font-size: 10px;
  color: var(--brand-blue-dark);
  background: var(--brand-blue-subtle);
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.settings-nav-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.settings-nav-list a {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
  transition: all 0.15s ease;
}

.settings-nav-list a:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.settings-nav-list a.active {
  background: var(--brand-blue-subtle);
  color: var(--brand-blue-dark);
  font-weight: 600;
}

.profile-settings-card {
  max-width: 760px;
}

.profile-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.profile-form-grid .wide {
  grid-column: 1 / -1;
}

.disabled-input {
  background: var(--bg-subtle);
  color: var(--text-tertiary);
  cursor: not-allowed;
}

.form-actions-row {
  grid-column: 1 / -1;
  padding-top: 12px;
  border-top: 1px solid var(--border-line);
}

@media (max-width: 768px) {
  .account-layout {
    grid-template-columns: 1fr;
  }
  .profile-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
