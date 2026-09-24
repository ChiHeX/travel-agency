<script setup>
import { computed, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { accountApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import MapPreview from '@/components/MapPreview.vue'
import AccountNav from '@/components/AccountNav.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loginLocation = computed(() => ({
  name: 'login',
  query: route.path.startsWith('/auth/') ? route.query : { redirect: route.fullPath }
}))
const unreadCount = ref(0)
const mapItinerary = ref([])
provide('setMapItinerary', (itinerary) => { mapItinerary.value = itinerary })
const mapPlaces = ref([])
const mapFocus = ref(null)
provide('setMapPlaces', (places) => { mapPlaces.value = places })
provide('setMapFocus', (place) => { mapFocus.value = place })
const sheetSize = ref(['articles', 'article-detail', 'attraction-detail'].includes(route.name) ? 'full' : 'half')
let dragStart = null
let sheetDragged = false

function startSheetDrag(event) {
  sheetDragged = false
  dragStart = event.clientY
  event.currentTarget.setPointerCapture(event.pointerId)
}
function endSheetDrag(event) {
  if (dragStart == null) return
  const delta = event.clientY - dragStart
  dragStart = null
  if (Math.abs(delta) < 30) return
  sheetDragged = true
  const levels = ['collapsed', 'half', 'full']
  sheetSize.value = levels[Math.max(0, Math.min(2, levels.indexOf(sheetSize.value) + (delta < 0 ? 1 : -1)))]
}
function toggleSheet() {
  if (sheetDragged) {
    sheetDragged = false
    return
  }
  sheetSize.value = sheetSize.value === 'full' ? 'half' : 'full'
}

// Sidebar expanded state: true = 180px with text, false = 56px with icon only
const isSidebarExpanded = ref(true)

// Middle Content Drawer open state: default false on homepage, true when user selects a module
const isDrawerOpen = ref(route.name !== 'home')

// Provide closeDrawer and isDrawerOpen to child views
function closeDrawer() {
  isDrawerOpen.value = false
  if (isMapActiveView.value) {
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
const guideViews = ['guides', 'latest-guides', 'city-guides', 'guide-publishers', 'publisher-guides', 'guide-detail']
const isMapActiveView = computed(() => ['home', 'search', 'routes', 'route-detail', 'articles', 'article-detail', 'attraction-detail', ...guideViews].includes(route.name))

async function refreshUnread() {
  if (auth.isLoggedIn) {
    try {
      unreadCount.value = (await accountApi.unreadCount())?.count || 0
    } catch (_) {}
  } else unreadCount.value = 0
}
provide('refreshUnread', refreshUnread)
watch(() => auth.isLoggedIn, refreshUnread, { immediate: true })
watch(() => route.path, () => {
  isDrawerOpen.value = route.name !== 'home'
  sheetSize.value = ['articles', 'article-detail', 'attraction-detail'].includes(route.name) ? 'full' : 'half'
  refreshUnread()
})

function handleTabClick(routeName) {
  if (!isDrawerOpen.value) {
    isDrawerOpen.value = true
    router.push({ name: routeName })
    return
  }

  const isCurrentActive =
    (routeName === 'search' && route.name === 'search') ||
    (routeName === 'guides' && guideViews.includes(route.name)) ||
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
    <nav class="mobile-navigation" aria-label="主导航">
      <RouterLink to="/" @click="goHome">行迹</RouterLink>
      <button @click="handleTabClick('search')">搜索</button>
      <button @click="handleTabClick('routes')">线路</button>
      <button @click="handleTabClick('guides')">指南</button>
      <RouterLink :to="auth.isLoggedIn ? '/account/profile' : loginLocation">{{ auth.isLoggedIn ? '我的' : '登录' }}</RouterLink>
    </nav>
    <!-- Main interactive map behind the content drawer -->
    <MapPreview
      v-if="isMapActiveView"
      :itinerary="mapItinerary"
      :places="mapPlaces"
      :focused-place="mapFocus"
      :drawer-open="isDrawerOpen"
      :sidebar-expanded="isSidebarExpanded"
      :sheet-size="sheetSize"
    />

    <!-- ==========================================================================
         2. Left Navigation Rail (Frosted Liquid Glass, Floating on Top)
         ========================================================================== -->
    <aside class="nav-sidebar-rail" :class="{ collapsed: !isSidebarExpanded }">
      <!-- Top Row: Logo + Brand on left, Toggle Button on right -->
      <div class="rail-top-bar">
        <a v-if="isSidebarExpanded" href="javascript:void(0)" class="project-brand-wrap" title="行迹旅行" @click="goHome">
          <div class="brand-badge-box">
            <AppIcon name="brand" size="16" />
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

        <!-- Tab 2: 指南 -->
        <button
          type="button"
          class="rail-tab-item"
          :class="{ active: isDrawerOpen && guideViews.includes(route.name) }"
          title="指南"
          @click="handleTabClick('guides')"
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

        <RouterLink v-else :to="loginLocation" class="login-action-btn" :class="{ 'icon-only': !isSidebarExpanded }">
          <span v-if="isSidebarExpanded">登录 / 注册</span>
          <span v-else>登录</span>
        </RouterLink>
      </div>
    </aside>

    <!-- ==========================================================================
         3. Middle Content Drawer (Frosted Liquid Glass, Emerging from Sidebar Right)
         ========================================================================== -->
    <div
      class="drawer-track-wrapper"
      :class="{
        'full-page-mode': !isMapActiveView,
        'sheet-half': sheetSize === 'half',
        'sheet-full': sheetSize === 'full',
        'sheet-peek': sheetSize === 'collapsed',
        'drawer-collapsed': !isDrawerOpen && isMapActiveView
      }"
    >
      <section
        class="drawer-container"
        :class="{
          'full-page-mode': !isMapActiveView
        }"
      >
        <div v-if="isMapActiveView" class="sheet-handle">
          <button type="button" :aria-expanded="sheetSize !== 'collapsed'" aria-label="切换抽屉高度" @pointerdown="startSheetDrag" @pointerup="endSheetDrag" @pointercancel="dragStart = null" @click="toggleSheet"><span></span></button>
          <div class="sheet-size-actions"><button @click="sheetSize = 'collapsed'">收起</button><button @click="sheetSize = 'half'">半屏</button><button @click="sheetSize = 'full'">展开</button></div>
        </div>
        <AccountNav v-if="route.path.startsWith('/account/')" />
        <div class="route-view-body"><RouterView :key="route.path" /></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ==========================================================================
   Full-screen map + floating content drawer
   ========================================================================== */

.app-layout-shell {
  --bg-canvas: var(--bg-secondary);
  --bg-subtle: #f8f9fb;
  --bg-hover: var(--bg-secondary);
  --border-line: var(--border-divider);
  --border-strong: #d2d2d7;
  --brand-blue: var(--theme-blue);
  --brand-blue-dark: #0062c4;
  --brand-blue-subtle: var(--theme-blue-tint);
  --brand-blue-tint: var(--theme-blue-tint);
  --price-orange: var(--price-color);
  --danger-red: var(--status-red);
  --danger-text: #c52019;
  --radius-xl: 20px;
  --shadow-xs: var(--shadow-subtle);
  --shadow-sm: var(--shadow-card);
  --shadow-xl: var(--shadow-popover);
  position: relative;
  width: 100vw;
  height: 100dvh;
  overflow: hidden;
  display: flex;
  background: #9ec9eb;
}

.mobile-navigation, .sheet-handle { display: none; }
.route-view-body { flex: 1; min-height: 0; min-width: 0; }
.drawer-container { display: flex; flex-direction: column; }
.drawer-container.full-page-mode .route-view-body { overflow-y: auto; }

/* ==========================================================================
   2. Left Navigation Rail (Frosted Liquid Glass, Floating on Top)
   ========================================================================== */

.nav-sidebar-rail {
  position: relative;
  z-index: 30;
  width: 180px;
  height: 100%;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  padding: 14px 10px;
  gap: 16px;
  overflow: hidden;
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
  display: grid;
  place-items: center;
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
  color: var(--text-secondary);
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
  background: rgba(0, 0, 0, 0.06);
  color: var(--text-primary);
}

.rail-tab-item.active .tab-icon-box {
  color: inherit;
}

.rail-tab-item.active .tab-title {
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
  background: var(--theme-blue);
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
  transition: transform 0.32s cubic-bezier(0.32, 0.72, 0, 1),
              opacity 0.28s ease;
  transform: translateX(0);
  opacity: 1;
  will-change: transform, opacity;
}

.drawer-track-wrapper.drawer-collapsed .drawer-container {
  transform: translateX(-100%);
  opacity: 0;
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
  .mobile-navigation { position: relative; z-index: 40; display: flex; align-items: center; justify-content: space-around; flex: 0 0 52px; padding-top: env(safe-area-inset-top); background: rgba(255,255,255,.94); border-bottom: 1px solid var(--border-divider); }
  .mobile-navigation a, .mobile-navigation button { padding: 10px; background: none; border: 0; color: var(--theme-blue); cursor: pointer; }
  .app-layout-shell {
    flex-direction: column;
  }
  .nav-sidebar-rail {
    display: none;
  }
  .drawer-track-wrapper {
    position: absolute;
    bottom: 0;
    width: 100%;
    height: 55%;
    border-radius: 20px 20px 0 0;
    transition: height .25s ease;
  }
  .drawer-track-wrapper.sheet-full { height: calc(100% - 52px - env(safe-area-inset-top)); }
  .drawer-track-wrapper.sheet-peek { height: 96px; }
  .drawer-track-wrapper.drawer-collapsed { height: 0; }
  .drawer-track-wrapper.full-page-mode { position: relative; flex: 1; min-height: 0; width: 100%; height: auto; border-radius: 0; }
  .drawer-container {
    width: 100%;
    height: 100%;
    box-shadow: none;
  }
  .sheet-handle { display: flex; flex-direction: column; align-items: center; flex-shrink: 0; touch-action: none; padding: 5px 12px; border-bottom: 1px solid var(--border-divider); }
  .sheet-handle > button { width: 80px; min-height: 20px; display: grid; place-items: center; border: 0; background: none; }
  .sheet-handle span { width: 36px; height: 4px; background: #b8b8bd; border-radius: 4px; }
  .sheet-size-actions { display: flex; gap: 20px; }
  .sheet-size-actions button { padding: 4px 12px; border: 0; background: none; color: var(--theme-blue); font-size: 11px; }
  .route-view-body { overflow: auto; }
}
</style>
