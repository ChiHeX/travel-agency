<script setup>
import { computed, onMounted, provide, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { accountApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import WorldMapCanvas from '@/components/WorldMapCanvas.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const unreadCount = ref(0)

// Sidebar expanded state: true = 180px with text, false = 56px with icon only
const isSidebarExpanded = ref(true)

// Middle Content Drawer open state: default false on homepage, true when user selects a module
const isDrawerOpen = ref(route.name !== 'home')

// Provide closeDrawer and isDrawerOpen to child views
function closeDrawer() {
  isDrawerOpen.value = false
  if (['search', 'routes', 'articles'].includes(route.name)) {
    router.push({ name: 'home' })
  }
}
function openDrawer() {
  isDrawerOpen.value = true
}
provide('closeDrawer', closeDrawer)
provide('openDrawer', openDrawer)
provide('isDrawerOpen', isDrawerOpen)

const isBackoffice = computed(() => auth.hasRole('ADMIN') || auth.hasRole('STAFF') || auth.hasRole('GUIDE'))
const isMapActiveView = computed(() => ['home', 'search', 'routes', 'route-detail', 'articles', 'article-detail'].includes(route.name))

onMounted(async () => {
  if (auth.isLoggedIn) {
    try {
      unreadCount.value = (await accountApi.unreadCount())?.count || 0
    } catch (_) {}
  }
})

function handleTabClick(routeName) {
  if (!isDrawerOpen.value) {
    isDrawerOpen.value = true
    router.push({ name: routeName })
    return
  }

  const isCurrentActive =
    (routeName === 'search' && route.name === 'search') ||
    (routeName === 'articles' && (route.name === 'articles' || route.name === 'article-detail')) ||
    (routeName === 'routes' && (route.name === 'routes' || route.name === 'route-detail'))

  if (isCurrentActive) {
    isDrawerOpen.value = false
    router.push({ name: 'home' })
  } else {
    router.push({ name: routeName })
  }
}

function goHome() {
  isDrawerOpen.value = false
  if (route.name !== 'home') {
    router.push({ name: 'home' })
  }
}

function toggleSidebar() {
  isSidebarExpanded.value = !isSidebarExpanded.value
}

function logout() {
  auth.logout()
  router.push('/')
}
</script>

<template>
  <div
    class="app-layout-shell"
    :class="{
      'sidebar-collapsed': !isSidebarExpanded,
      'drawer-closed': !isDrawerOpen && isMapActiveView
    }"
  >
    <!-- ==========================================================================
         1. Full-Screen 360° Infinite Continuous World Map Canvas (Underneath)
         ========================================================================== -->
    <WorldMapCanvas
      v-if="isMapActiveView"
      :pins="[]"
    />

    <!-- ==========================================================================
         2. Left Navigation Rail (Frosted Liquid Glass, Floating on Top)
         ========================================================================== -->
    <aside class="nav-sidebar-rail" :class="{ collapsed: !isSidebarExpanded }">
      <!-- Top Row: Logo + Brand on left, Toggle Button on right -->
      <div class="rail-top-bar">
        <a v-if="isSidebarExpanded" href="javascript:void(0)" class="project-brand-wrap" title="行迹旅行" @click="goHome">
          <div class="brand-badge-box">
            <AppIcon name="brand" size="16" color="#ffffff" />
          </div>
          <span class="brand-title">行迹</span>
        </a>

        <button
          type="button"
          class="rail-toggle-btn"
          :title="isSidebarExpanded ? '收起边栏说明' : '展开边栏说明'"
          @click="toggleSidebar"
        >
          <AppIcon name="sidebar" size="18" />
        </button>
      </div>

      <!-- Vertical Tab List (Icon on left, Text on right) -->
      <nav class="rail-vertical-tabs">
        <!-- Tab 1: 搜索 (/search) -->
        <button
          type="button"
          class="rail-tab-item"
          :class="{ active: isDrawerOpen && route.name === 'search' }"
          title="搜索"
          @click="handleTabClick('search')"
        >
          <div class="tab-icon-box">
            <AppIcon name="search" size="18" />
          </div>
          <span v-if="isSidebarExpanded" class="tab-title">搜索</span>
        </button>

        <!-- Tab 2: 指南 (Articles / Guides) -->
        <button
          type="button"
          class="rail-tab-item"
          :class="{ active: isDrawerOpen && (route.name === 'articles' || route.name === 'article-detail') }"
          title="指南"
          @click="handleTabClick('articles')"
        >
          <div class="tab-icon-box">
            <AppIcon name="guides" size="18" />
          </div>
          <span v-if="isSidebarExpanded" class="tab-title">指南</span>
        </button>

        <!-- Tab 3: 路线 (Routes & Itineraries) -->
        <button
          type="button"
          class="rail-tab-item"
          :class="{ active: isDrawerOpen && (route.name === 'routes' || route.name === 'route-detail') }"
          title="路线"
          @click="handleTabClick('routes')"
        >
          <div class="tab-icon-box">
            <AppIcon name="routes" size="18" />
          </div>
          <span v-if="isSidebarExpanded" class="tab-title">路线</span>
        </button>
      </nav>

      <!-- Bottom User & Setting Actions -->
      <div class="rail-bottom-actions">
        <RouterLink
          v-if="auth.isLoggedIn"
          to="/account/messages"
          class="rail-action-row"
          title="消息中心"
        >
          <div class="action-icon-box">
            <AppIcon name="bell" size="16" />
            <span v-if="unreadCount" class="rail-dot">{{ unreadCount }}</span>
          </div>
          <span v-if="isSidebarExpanded" class="action-label">消息</span>
        </RouterLink>

        <RouterLink
          v-if="isBackoffice"
          :to="auth.hasRole('GUIDE') && !auth.hasRole('STAFF') && !auth.hasRole('ADMIN') ? '/guide' : '/admin'"
          class="rail-action-row"
          title="工作台"
        >
          <div class="action-icon-box">
            <AppIcon name="work" size="16" />
          </div>
          <span v-if="isSidebarExpanded" class="action-label">工作台</span>
        </RouterLink>

        <div v-if="auth.isLoggedIn" class="user-profile-row">
          <RouterLink to="/account/profile" class="rail-user-avatar" :title="auth.user?.nickname || auth.user?.username">
            {{ (auth.user?.nickname || auth.user?.username || 'U').slice(0, 1).toUpperCase() }}
          </RouterLink>
          <div v-if="isSidebarExpanded" class="user-name-col">
            <span class="user-nickname">{{ auth.user?.nickname || auth.user?.username }}</span>
            <button class="logout-link-btn" type="button" @click="logout">退出</button>
          </div>
        </div>

        <RouterLink v-else to="/auth/login" class="login-action-btn" :class="{ 'icon-only': !isSidebarExpanded }">
          <span v-if="isSidebarExpanded">登录 / 注册</span>
          <span v-else>登录</span>
        </RouterLink>

        <!-- Copyright Notice at Bottom -->
        <div v-if="isSidebarExpanded" class="rail-copyright-note">
          <span>Copyright © 2026 行迹旅行. 保留所有权利.</span>
        </div>
      </div>
    </aside>

    <!-- ==========================================================================
         3. Middle Content Drawer (Frosted Liquid Glass, Emerging from Sidebar Right)
         ========================================================================== -->
    <div
      class="drawer-track-wrapper"
      :class="{
        'full-page-mode': !isMapActiveView,
        'drawer-collapsed': !isDrawerOpen && isMapActiveView
      }"
    >
      <section
        class="drawer-container"
        :class="{
          'full-page-mode': !isMapActiveView
        }"
      >
        <RouterView />
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ==========================================================================
   Full-Bleed Map + Floating Frosted Glass UI Architecture
   ========================================================================== */

.app-layout-shell {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  display: flex;
  background: #9ec9eb;
}

/* ==========================================================================
   2. Left Navigation Rail (Frosted Liquid Glass, Floating on Top)
   ========================================================================== */

.nav-sidebar-rail {
  position: relative;
  z-index: 30;
  width: 180px;
  height: 100%;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.76);
  backdrop-filter: blur(28px) saturate(190%);
  -webkit-backdrop-filter: blur(28px) saturate(190%);
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  padding: 14px 10px;
  gap: 16px;
  overflow: hidden;
  box-shadow: 2px 0 16px rgba(0, 0, 0, 0.04);
  transition: width 0.25s cubic-bezier(0.16, 1, 0.3, 1), padding 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.nav-sidebar-rail.collapsed {
  width: 56px;
  padding: 14px 6px;
}

.rail-top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 36px;
  padding: 0 4px;
}

.nav-sidebar-rail.collapsed .rail-top-bar {
  justify-content: center;
  padding: 0;
}

.project-brand-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-primary);
  text-decoration: none;
}

.brand-badge-box {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #0071e3 0%, #0056b3 100%);
  display: grid;
  place-items: center;
  box-shadow: 0 2px 6px rgba(0, 113, 227, 0.35);
  flex-shrink: 0;
}

.brand-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.02em;
}

.rail-toggle-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.rail-toggle-btn:hover {
  background: rgba(0, 0, 0, 0.06);
  color: var(--text-primary);
}

/* Vertical Tab List */
.rail-vertical-tabs {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.rail-tab-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 10px;
  background: transparent;
  border: none;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  width: 100%;
  text-align: left;
  transition: all 0.15s ease;
}

.nav-sidebar-rail.collapsed .rail-tab-item {
  justify-content: center;
  padding: 6px 0;
}

.tab-icon-box {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #86868b;
  flex-shrink: 0;
  transition: all 0.15s ease;
}

.rail-tab-item:hover {
  background: rgba(0, 0, 0, 0.04);
}

.rail-tab-item:hover .tab-icon-box {
  color: var(--text-primary);
}

.rail-tab-item.active {
  background: rgba(0, 0, 0, 0.05);
}

.rail-tab-item.active .tab-icon-box {
  background: var(--theme-blue);
  color: #ffffff;
  box-shadow: 0 2px 6px rgba(0, 113, 227, 0.35);
}

.rail-tab-item.active .tab-title {
  color: var(--theme-blue);
  font-weight: 600;
}

/* Bottom Actions in Sidebar */
.rail-bottom-actions {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rail-action-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 10px;
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 12px;
  text-decoration: none;
  transition: all 0.15s ease;
}

.nav-sidebar-rail.collapsed .rail-action-row {
  justify-content: center;
  padding: 6px 0;
}

.rail-action-row:hover {
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-primary);
}

.action-icon-box {
  position: relative;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
}

.rail-dot {
  position: absolute;
  top: -2px;
  right: -2px;
  background: var(--status-red);
  color: white;
  font-size: 9px;
  font-weight: 700;
  min-width: 14px;
  height: 14px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}

.user-profile-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.nav-sidebar-rail.collapsed .user-profile-row {
  justify-content: center;
  padding: 6px 0;
}

.rail-user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #5856d6;
  color: white;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 12px;
  flex-shrink: 0;
  text-decoration: none;
}

.user-name-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-nickname {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.logout-link-btn {
  background: transparent;
  border: none;
  color: #8e8e93;
  font-size: 11px;
  cursor: pointer;
  padding: 0;
  text-align: left;
}

.logout-link-btn:hover {
  color: var(--status-red);
}

.login-action-btn {
  display: block;
  text-align: center;
  padding: 6px 12px;
  border-radius: var(--radius-pill);
  background: var(--theme-blue-tint);
  color: var(--theme-blue);
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
}

.login-action-btn.icon-only {
  padding: 6px 0;
  font-size: 10px;
}

.rail-copyright-note {
  font-size: 10px;
  color: #a1a1a6;
  line-height: 1.3;
  padding: 4px 4px 0;
}

/* ==========================================================================
   3. Middle Content Drawer Track & Container (Anchored to Sidebar Right Edge)
   ========================================================================== */

.drawer-track-wrapper {
  position: relative;
  z-index: 20;
  width: 400px;
  height: 100%;
  flex-shrink: 0;
  overflow: hidden; /* Clips animation strictly at the right edge of the sidebar */
  transition: width 0.32s cubic-bezier(0.32, 0.72, 0, 1);
  pointer-events: auto;
}

.drawer-track-wrapper.drawer-collapsed {
  width: 0 !important;
  pointer-events: none !important;
}

.drawer-track-wrapper.full-page-mode {
  flex: 1;
  width: auto;
  overflow: visible;
}

.drawer-container {
  position: relative;
  width: 400px;
  height: 100%;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(30px) saturate(190%);
  -webkit-backdrop-filter: blur(30px) saturate(190%);
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  overflow-y: auto;
  overflow-x: hidden;
  box-shadow: 4px 0 24px rgba(0, 0, 0, 0.05);
  transition: transform 0.32s cubic-bezier(0.32, 0.72, 0, 1),
              opacity 0.28s ease,
              box-shadow 0.32s ease;
  transform: translateX(0);
  opacity: 1;
  will-change: transform, opacity;
}

.drawer-track-wrapper.drawer-collapsed .drawer-container {
  transform: translateX(-100%);
  opacity: 0;
  box-shadow: none;
}

.drawer-container.full-page-mode {
  width: 100%;
  flex: 1;
  background: #ffffff;
  backdrop-filter: none;
  z-index: 35;
  box-shadow: none;
}

/* Responsive */
@media (max-width: 900px) {
  .app-layout-shell {
    flex-direction: column;
  }
  .nav-sidebar-rail {
    display: none;
  }
  .drawer-track-wrapper {
    width: 100vw;
    height: 100vh;
  }
  .drawer-container {
    width: 100vw;
    height: 100vh;
    box-shadow: none;
  }
}
</style>
