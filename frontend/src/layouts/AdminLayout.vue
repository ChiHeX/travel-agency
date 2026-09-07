<script setup>
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isGuideOnly = computed(() => auth.hasRole('GUIDE') && !auth.hasRole('ADMIN') && !auth.hasRole('STAFF'))
const title = computed(() => (isGuideOnly.value ? '导游工作台' : '旅行社管理系统'))

function logout() {
  auth.logout()
  router.push('/auth/login')
}
</script>

<template>
  <div class="admin-shell">
    <!-- Modern Slate Sidebar -->
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" to="/">
        <span class="brand-mark">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <polygon points="3 11 22 2 13 21 11 13 3 11" />
          </svg>
        </span>
        <div class="brand-text">
          <strong>{{ title }}</strong>
          <small>WORKSPACE</small>
        </div>
      </RouterLink>

      <div class="sidebar-caption">业务运营</div>
      <nav class="side-nav" v-if="isGuideOnly">
        <RouterLink to="/guide/dashboard">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7" /><rect x="14" y="3" width="7" height="7" /><rect x="14" y="14" width="7" height="7" /><rect x="3" y="14" width="7" height="7" />
          </svg>
          工作台概览
        </RouterLink>
        <RouterLink to="/guide/departures">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2" /><line x1="16" y1="2" x2="16" y2="6" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="3" y1="10" x2="21" y2="10" />
          </svg>
          我的带团行程
        </RouterLink>
      </nav>

      <nav class="side-nav" v-else>
        <RouterLink to="/admin/dashboard">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7" /><rect x="14" y="3" width="7" height="7" /><rect x="14" y="14" width="7" height="7" /><rect x="3" y="14" width="7" height="7" />
          </svg>
          经营大盘 (Dashboard)
        </RouterLink>
        <RouterLink to="/admin/routes">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polygon points="3 11 22 2 13 21 11 13 3 11" />
          </svg>
          跟团线路管理
        </RouterLink>
        <RouterLink to="/admin/departures">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2" /><line x1="16" y1="2" x2="16" y2="6" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="3" y1="10" x2="21" y2="10" />
          </svg>
          团期班次排期
        </RouterLink>
        <RouterLink to="/admin/orders">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 0 1-8 0" />
          </svg>
          跟团报名订单
        </RouterLink>
        <RouterLink to="/admin/refunds">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="1 4 1 10 7 10" /><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
          </svg>
          退款申请审核
        </RouterLink>

        <div class="sidebar-caption nested-caption">基础资源</div>
        <RouterLink to="/admin/attractions">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" /><circle cx="12" cy="10" r="3" />
          </svg>
          景点资料库
        </RouterLink>
        <RouterLink to="/admin/hotels">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          </svg>
          酒店合作资料
        </RouterLink>
        <RouterLink to="/admin/guides">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" />
          </svg>
          导游团队档案
        </RouterLink>

        <template v-if="auth.hasRole('ADMIN')">
          <div class="sidebar-caption nested-caption">系统权限与设置</div>
          <RouterLink to="/admin/users">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" />
            </svg>
            用户注册管理
          </RouterLink>
          <RouterLink to="/admin/staff">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="4" /><polyline points="17 11 19 13 23 9" />
            </svg>
            内部人员权限
          </RouterLink>
        </template>
      </nav>

      <RouterLink class="back-home" to="/">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 18 9 12 15 6" />
        </svg>
        返回游客端前台
      </RouterLink>
    </aside>

    <!-- Admin Main Container -->
    <section class="admin-main">
      <header class="admin-topbar">
        <div class="topbar-title-wrap">
          <span class="eyebrow">{{ route.meta?.section || 'MANAGEMENT' }}</span>
          <h1>{{ route.meta?.pageTitle || title }}</h1>
        </div>

        <div class="admin-user">
          <span class="tag primary">{{ auth.roles.join(' · ') }}</span>
          <span class="user-name"><strong>{{ auth.user?.nickname || auth.user?.username }}</strong></span>
          <button class="secondary-button logout-btn" @click="logout">退出后台</button>
        </div>
      </header>

      <main class="admin-content">
        <RouterView />
      </main>
    </section>
  </div>
</template>

<style scoped>
.topbar-title-wrap h1 {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.user-name strong {
  font-weight: 600;
  color: var(--text-primary);
}

.logout-btn {
  min-height: 30px;
  padding: 0 10px;
  font-size: 11px;
}
</style>
