<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const router = useRouter()
const currentRoute = useRoute()
const closeDrawer = inject('closeDrawer', () => {})

const keyword = ref(currentRoute.query.keyword || '')
const routes = ref([])
const total = ref(0)
const page = ref(Number(currentRoute.query.page) || 1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')
const destinations = computed(() => [...new Set(routes.value.map((item) => item.destination).filter(Boolean))])

async function loadRoutes() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await routeApi.list({
      keyword: keyword.value || undefined,
      hasDeparture: true,
      page: page.value,
      size: pageSize
    })
    routes.value = data?.items || []
    total.value = data?.total || 0
  } catch (error) {
    routes.value = []
    total.value = 0
    errorMessage.value = error.message || '搜索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function search(val = keyword.value) {
  keyword.value = val
  page.value = 1
  router.replace({ name: 'search', query: keyword.value ? { keyword: keyword.value } : {} })
  loadRoutes()
}

function changePage(nextPage) {
  page.value = nextPage
  router.replace({ name: 'search', query: { ...(keyword.value ? { keyword: keyword.value } : {}), page: String(page.value) } })
  loadRoutes()
}

function openRoute(id) {
  router.push({ name: 'route-detail', params: { id } })
}

onMounted(loadRoutes)
</script>

<template>
  <div class="search-drawer-panel">
    <!-- Top Header: 搜索 + ✕ -->
    <div class="drawer-header-bar">
      <h2>搜索</h2>
      <button type="button" class="drawer-close-btn" title="关闭面板" @click="closeDrawer">
        <AppIcon name="close" size="13" color="#86868b" />
      </button>
    </div>

    <!-- Search Input Bar -->
    <div class="drawer-search-box">
      <form class="search-input-pill" @submit.prevent="search()">
        <AppIcon name="search" size="15" color="#8e8e93" />
        <input
          v-model="keyword"
          placeholder="搜索目的地或线路"
          aria-label="搜索地点或线路"
          autofocus
        />
        <button v-if="keyword" type="button" class="clear-btn" @click="search('')">
          <AppIcon name="close" size="10" color="#ffffff" />
        </button>
      </form>
    </div>

    <!-- Drawer Scrollable Content Body -->
    <div class="drawer-scroll-body">
      <div v-if="destinations.length" class="category-pills-section">
        <h4 class="section-title">线路目的地</h4>
        <div class="destination-pills-row">
          <button v-for="destination in destinations" :key="destination" type="button" class="destination-pill" @click="search(destination)">
            <AppIcon name="pin" size="13" color="#0071e3" />
            <span>{{ destination }}</span>
          </button>
        </div>
      </div>

      <!-- Section: 可报名跟团路线 (Results Feed) -->
      <div class="results-section">
        <div class="results-title-row">
          <h4 class="section-title">可报名路线 ({{ total }})</h4>
          <RouterLink :to="{ name: 'routes', query: keyword ? { keyword } : undefined }" class="view-all-link">
            <span>路线规划</span>
            <AppIcon name="chevron-right" size="12" />
          </RouterLink>
        </div>

        <div v-if="loading" class="skeleton-list">
          <el-skeleton v-for="i in 3" :key="i" :rows="3" animated style="margin-bottom: 12px;" />
        </div>

        <div v-else-if="errorMessage" class="empty-results error-results">
          <strong>搜索暂时不可用</strong>
          <p>{{ errorMessage }}</p>
          <button type="button" class="secondary-button" @click="loadRoutes">重新加载</button>
        </div>

        <div v-else-if="routes.length" class="route-cards-feed">
          <div
            v-for="item in routes"
            :key="item.id"
            class="place-card-item"
            @click="openRoute(item.id)"
          >
            <div class="place-thumb">
              <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.name" loading="lazy" />
              <div v-else class="place-thumb-fallback">
                <span>{{ item.destination?.slice(0, 2) || '—' }}</span>
              </div>
              <span class="duration-pill">{{ item.durationDays }} 日游</span>
            </div>

            <div class="place-info">
              <h5 :title="item.name">{{ item.name }}</h5>
              <div class="place-route-meta">
                <span>{{ item.departureCity }} 出发</span>
                <span>·</span>
                <span>目的地 {{ item.destination }}</span>
              </div>
              <div class="place-bottom-row">
                <span class="rating-badge" v-if="item.ratingCount">
                  <AppIcon name="star" size="11" color="#ff9500" />
                  <span>{{ item.ratingAvg }}</span>
                </span>
                <span class="rating-badge muted-rating" v-else>暂无评分</span>

                <div class="price-figure">
                  <template v-if="item.minAdultPrice != null">
                    <strong>¥{{ item.minAdultPrice }}</strong>
                    <small>起/人</small>
                  </template>
                  <small v-else>价格待发布</small>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="empty-results">
          <p>未找到完全匹配的线路，请尝试其他关键词。</p>
          <button type="button" class="secondary-button" @click="search('')">
            查看全部路线
          </button>
        </div>

        <div v-if="!loading && !errorMessage && total > pageSize" class="pagination-wrap">
          <el-pagination
            :current-page="page"
            :page-size="pageSize"
            :total="total"
            background
            layout="prev, pager, next"
            @current-change="changePage"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-drawer-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

.drawer-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px 10px;
}

.drawer-header-bar h2 {
  font-size: 20px;
  font-weight: 700;
  color: #1d1d1f;
  margin: 0;
  letter-spacing: -0.01em;
}

.drawer-close-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.05);
  border: none;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.drawer-close-btn:hover {
  background: rgba(0, 0, 0, 0.1);
}

.drawer-search-box {
  padding: 4px 18px 14px;
}

.search-input-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: var(--radius-sm);
  padding: 0 12px;
  height: 38px;
}

.search-input-pill input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  outline: none;
  font-size: 13px;
  color: var(--text-primary);
}

.search-input-pill input::placeholder {
  color: #8e8e93;
}

.clear-btn {
  background: #c7c7cc;
  border: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  cursor: pointer;
}

.drawer-scroll-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 18px 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: #1d1d1f;
  margin: 0 0 10px;
}

.destination-pills-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.destination-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid rgba(0, 0, 0, 0.07);
  background: rgba(255, 255, 255, 0.78);
  border-radius: var(--radius-pill);
  padding: 7px 11px;
  color: var(--text-primary);
  font-size: 12px;
  cursor: pointer;
}

.destination-pill:hover {
  background: #fff;
  border-color: var(--theme-blue);
}

.muted-rating {
  color: var(--text-tertiary);
  font-weight: 500;
}

.results-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.view-all-link {
  font-size: 12px;
  color: var(--theme-blue);
  display: inline-flex;
  align-items: center;
  gap: 2px;
  text-decoration: none;
}

.route-cards-feed {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.place-card-item {
  display: grid;
  grid-template-columns: 88px 1fr;
  gap: 12px;
  padding: 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(0, 0, 0, 0.05);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  cursor: pointer;
  transition: all 0.15s ease;
}

.place-card-item:hover {
  background: #ffffff;
  border-color: rgba(0, 0, 0, 0.1);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.place-thumb {
  position: relative;
  width: 88px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  background: #e5e5ea;
}

.place-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.place-thumb-fallback {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: var(--theme-blue);
  color: white;
  font-weight: 700;
  font-size: 14px;
}

.duration-pill {
  position: absolute;
  top: 4px;
  left: 4px;
  background: rgba(0, 0, 0, 0.65);
  color: white;
  font-size: 9px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
}

.place-info {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
}

.place-info h5 {
  font-size: 13px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.place-route-meta {
  font-size: 11px;
  color: var(--text-secondary);
  display: flex;
  gap: 4px;
}

.place-bottom-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.rating-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--status-orange);
  font-weight: 600;
}

.price-figure {
  display: flex;
  align-items: baseline;
  color: var(--price-color);
}

.price-figure strong {
  font-size: 14px;
  font-weight: 700;
}

.price-figure small {
  font-size: 10px;
  color: var(--text-secondary);
  margin-left: 2px;
}

.empty-results {
  padding: 30px 10px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}

.error-results strong {
  color: var(--text-primary);
  font-size: 13px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 8px 0 2px;
}

.empty-results p {
  margin-bottom: 12px;
}
</style>
