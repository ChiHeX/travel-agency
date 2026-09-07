<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const currentRoute = useRoute()
const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})

const form = reactive({
  keyword: currentRoute.query.keyword || '',
  departureCity: currentRoute.query.departureCity || '',
  destination: currentRoute.query.destination || '',
  durationDays: currentRoute.query.durationDays || '',
  departureMonth: currentRoute.query.departureMonth || '',
  minPrice: currentRoute.query.minPrice || '',
  maxPrice: currentRoute.query.maxPrice || '',
  hasDeparture: currentRoute.query.hasDeparture !== 'false',
  sortBy: currentRoute.query.sortBy || ''
})

const routes = ref([])
const total = ref(0)
const page = ref(Number(currentRoute.query.page) || 1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')
const showAdvancedFilters = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function buildParams() {
  const sortMap = {
    priceAsc: 'minAdultPrice,asc',
    priceDesc: 'minAdultPrice,desc',
    bookingCount: 'validBookingCount,desc',
    rating: 'ratingAvg,desc'
  }
  return {
    keyword: form.keyword.trim() || undefined,
    departureCity: form.departureCity.trim() || undefined,
    destination: form.destination.trim() || undefined,
    durationDays: form.durationDays || undefined,
    departureMonth: form.departureMonth || undefined,
    minPrice: form.minPrice === '' ? undefined : String(form.minPrice),
    maxPrice: form.maxPrice === '' ? undefined : String(form.maxPrice),
    hasDeparture: form.hasDeparture ? true : undefined,
    sort: sortMap[form.sortBy] || undefined,
    page: page.value,
    size: pageSize
  }
}

function syncQuery() {
  const query = Object.fromEntries(
    Object.entries({
      keyword: form.keyword,
      departureCity: form.departureCity,
      destination: form.destination,
      durationDays: form.durationDays,
      departureMonth: form.departureMonth,
      minPrice: form.minPrice,
      maxPrice: form.maxPrice,
      hasDeparture: form.hasDeparture ? 'true' : 'false',
      sortBy: form.sortBy,
      page: page.value > 1 ? String(page.value) : ''
    }).filter(([, value]) => value !== '' && value != null)
  )
  router.replace({ name: 'routes', query })
}

async function load(options = {}) {
  if (options.resetPage) page.value = 1
  loading.value = true
  errorMessage.value = ''
  syncQuery()
  try {
    const data = await routeApi.list(buildParams())
    routes.value = data?.items || []
    total.value = data?.total || 0
    if (data?.page && data.page !== page.value) page.value = data.page
  } catch (error) {
    routes.value = []
    total.value = 0
    errorMessage.value = error.message || '线路加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function changePage(nextPage) {
  page.value = nextPage
  load()
}

function openRoute(id) {
  router.push({ name: 'route-detail', params: { id } })
}

function resetFilters() {
  Object.assign(form, {
    keyword: '',
    departureCity: '',
    destination: '',
    durationDays: '',
    departureMonth: '',
    minPrice: '',
    maxPrice: '',
    hasDeparture: true,
    sortBy: ''
  })
  load({ resetPage: true })
}

function toggleAvailability() {
  form.hasDeparture = !form.hasDeparture
  load({ resetPage: true })
}

function applyFilters() {
  if (form.minPrice !== '' && form.maxPrice !== '' && Number(form.minPrice) > Number(form.maxPrice)) {
    errorMessage.value = '最低价格不能高于最高价格'
    return
  }
  load({ resetPage: true })
}

onMounted(load)
</script>

<template>
  <div class="route-drawer-panel">
    <!-- Header: 路线 ↺ ✕ (Screenshot 3) -->
    <div class="drawer-header-bar">
      <h2>路线</h2>
      <div class="header-action-icons">
        <button type="button" class="drawer-icon-btn" title="重置筛选" @click="resetFilters">
          <AppIcon name="refresh" size="13" />
        </button>
        <button type="button" class="drawer-icon-btn" title="关闭面板" @click="closeDrawer">
          <AppIcon name="close" size="13" />
        </button>
      </div>
    </div>

    <div class="drawer-scroll-body">
      <form class="drawer-search-box" @submit.prevent="load({ resetPage: true })">
        <AppIcon name="search" size="14" color="#8e8e93" />
        <input v-model="form.keyword" type="search" placeholder="搜索线路名称或景点" aria-label="搜索线路名称或景点" />
        <button v-if="form.keyword" type="button" class="clear-search-btn" title="清空关键词" @click="form.keyword = ''; load({ resetPage: true })">
          <AppIcon name="close" size="10" color="#ffffff" />
        </button>
      </form>

      <!-- Waypoint Inputs with Vertical Connector (Screenshot 3) -->
      <div class="waypoints-card-box">
        <!-- Start Point (起点) -->
        <div class="waypoint-row">
          <span class="waypoint-bullet blue">
            <AppIcon name="circle" size="13" color="#0071e3" />
          </span>
          <div class="waypoint-input-box">
            <span class="input-sub">起点 / 出发城市</span>
            <input v-model="form.departureCity" placeholder="输入出发城市" @change="load" />
          </div>
          <span class="waypoint-drag">
            <AppIcon name="drag" size="14" color="#8e8e93" />
          </span>
        </div>

        <div class="vertical-connector-line"></div>

        <!-- End Point (终点) -->
        <div class="waypoint-row">
          <span class="waypoint-bullet blue">
            <AppIcon name="pin" size="13" color="#0071e3" />
          </span>
          <div class="waypoint-input-box">
            <span class="input-sub">终点 / 目的地</span>
            <input v-model="form.destination" placeholder="输入目的地" @change="load" />
          </div>
          <span class="waypoint-drag">
            <AppIcon name="drag" size="14" color="#8e8e93" />
          </span>
        </div>
      </div>

      <!-- Options Dropdowns (Screenshot 3) -->
      <div class="options-pills-row primary-filters-row">
        <div class="pill-dropdown">
          <select v-model="form.durationDays" aria-label="出游天数" @change="applyFilters">
            <option value="">出游天数：全部</option>
            <option v-for="d in [3, 4, 5, 6, 7, 8, 10]" :key="d" :value="d">{{ d }} 天行程</option>
          </select>
        </div>
        <div class="pill-dropdown">
          <select v-model="form.departureMonth" aria-label="出发月份" @change="applyFilters">
            <option value="">出发月份：全部</option>
            <option v-for="month in 12" :key="month" :value="month">{{ month }} 月出发</option>
          </select>
        </div>
        <button type="button" class="pill-btn" :class="{ active: form.hasDeparture }" @click="toggleAvailability">
          <AppIcon name="filter" size="11" />
          <span>{{ form.hasDeparture ? '仅可报名' : '全部线路' }}</span>
        </button>
      </div>

      <details class="advanced-filter-panel" :open="showAdvancedFilters" @toggle="showAdvancedFilters = $event.target.open">
        <summary>价格范围与更多筛选</summary>
        <div class="advanced-filter-fields">
          <label>
            <span>最低价格</span>
            <input v-model="form.minPrice" type="number" min="0" inputmode="numeric" placeholder="不限" @keyup.enter="applyFilters" />
          </label>
          <span class="price-range-separator">—</span>
          <label>
            <span>最高价格</span>
            <input v-model="form.maxPrice" type="number" min="0" inputmode="numeric" placeholder="不限" @keyup.enter="applyFilters" />
          </label>
          <button type="button" class="filter-apply-btn" @click="applyFilters">应用</button>
        </div>
      </details>

      <!-- Route Plans / Results List (地图 Route Cards) -->
      <div class="route-plans-section">
        <div class="plans-heading-row">
          <h4 class="plans-title">匹配跟团游方案 <span v-if="!loading" class="result-count">{{ total }}</span></h4>
          <select v-model="form.sortBy" class="sort-select" aria-label="线路排序" @change="applyFilters">
            <option value="">综合排序</option>
            <option value="priceAsc">价格从低到高</option>
            <option value="priceDesc">价格从高到低</option>
            <option value="bookingCount">报名人数</option>
            <option value="rating">用户评分</option>
          </select>
        </div>

        <div v-if="loading" class="skeleton-wrap">
          <el-skeleton v-for="i in 3" :key="i" :rows="3" animated style="margin-bottom: 10px;" />
        </div>

        <div v-else-if="errorMessage" class="request-error-box">
          <AppIcon name="pin" size="18" color="#ff3b30" />
          <p>{{ errorMessage }}</p>
          <button type="button" class="secondary-button" @click="load()">重新加载</button>
        </div>

        <div v-else-if="routes.length" class="route-plans-list">
          <div
            v-for="item in routes"
            :key="item.id"
            class="route-plan-card"
            @click="openRoute(item.id)"
          >
            <div class="plan-card-left">
              <span class="mode-icon">
                <AppIcon name="bus" size="18" color="#0071e3" />
              </span>
              <div class="plan-info">
                <h5>{{ item.name }}</h5>
                <span class="plan-specs">{{ item.departureCity }} 出发 · {{ item.durationDays }} 日行程 · {{ item.destination }}</span>
              </div>
            </div>

            <div class="plan-card-right">
              <strong v-if="item.minAdultPrice != null" class="plan-price">¥{{ item.minAdultPrice }}</strong>
              <span v-else class="plan-price pending-price">价格待发布</span>
              <AppIcon name="chevron-right" size="14" color="#8e8e93" />
            </div>
          </div>
        </div>

        <div v-else class="empty-box">
          暂无匹配的路线方案，请调整目的地或出发城市。
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
.route-drawer-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

/* Header (Screenshot 3: 路线 📤 ✕) */
.drawer-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.drawer-header-bar h2 {
  font-size: 20px;
  font-weight: 700;
  color: #1d1d1f;
  margin: 0;
}

.header-action-icons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-icon-btn {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.05);
  border: none;
  font-size: 14px;
  color: var(--text-secondary);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.drawer-icon-btn:hover {
  background: rgba(0, 0, 0, 0.08);
  color: var(--text-primary);
}

/* Scroll Body */
.drawer-scroll-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.drawer-search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  background: rgba(0, 0, 0, 0.05);
}

.drawer-search-box input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text-primary);
  font-size: 13px;
}

.drawer-search-box input::placeholder {
  color: #8e8e93;
}

.clear-search-btn {
  display: grid;
  width: 16px;
  height: 16px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #c7c7cc;
  cursor: pointer;
}

/* Transport Mode Switcher (Screenshot 3) */
.transport-mode-switch {
  display: flex;
  background: rgba(0, 0, 0, 0.06);
  padding: 3px;
  border-radius: var(--radius-sm);
  gap: 2px;
}

.mode-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 7px 0;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #1d1d1f;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  transition: all 0.15s ease;
}

.mode-btn.active {
  background: var(--theme-blue);
  color: #ffffff;
  font-weight: 600;
}

/* Waypoints Card (Screenshot 3) */
.waypoints-card-box {
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
}

.waypoint-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.waypoint-bullet {
  display: flex;
  align-items: center;
}

.waypoint-input-box {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.input-sub {
  font-size: 9px;
  color: var(--text-tertiary);
  text-transform: uppercase;
}

.waypoint-input-box input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  color: #1d1d1f;
  padding: 2px 0;
}

.waypoint-drag {
  display: flex;
  align-items: center;
}

.vertical-connector-line {
  width: 2px;
  height: 18px;
  background: rgba(0, 0, 0, 0.08);
  margin-left: 6px;
  margin-top: 3px;
  margin-bottom: 3px;
}

/* Option Dropdowns (Screenshot 3) */
.options-pills-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.primary-filters-row .pill-dropdown {
  min-width: 0;
  flex: 1 1 120px;
}

.pill-dropdown select {
  height: 32px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.7);
  padding: 0 12px;
  font-size: 12px;
  color: #1d1d1f;
  outline: none;
}

.pill-btn {
  height: 32px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.7);
  padding: 0 12px;
  font-size: 12px;
  color: #1d1d1f;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.pill-btn.active {
  border-color: rgba(0, 113, 227, 0.35);
  color: var(--theme-blue);
  background: var(--theme-blue-tint);
}

.advanced-filter-panel {
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  padding: 9px 0;
}

.advanced-filter-panel summary {
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.advanced-filter-panel summary::-webkit-details-marker {
  display: none;
}

.advanced-filter-panel summary::after {
  content: '＋';
  float: right;
  color: var(--text-tertiary);
}

.advanced-filter-panel[open] summary::after {
  content: '−';
}

.advanced-filter-fields {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding-top: 10px;
}

.advanced-filter-fields label {
  display: grid;
  flex: 1;
  gap: 4px;
}

.advanced-filter-fields label span {
  color: var(--text-tertiary);
  font-size: 10px;
}

.advanced-filter-fields input {
  width: 100%;
  height: 30px;
  box-sizing: border-box;
  padding: 0 8px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 7px;
  outline: 0;
  background: rgba(255, 255, 255, 0.75);
  font-size: 12px;
}

.price-range-separator {
  padding-bottom: 7px;
  color: var(--text-tertiary);
  font-size: 12px;
}

.filter-apply-btn {
  height: 30px;
  padding: 0 10px;
  border: 0;
  border-radius: 7px;
  color: #fff;
  background: var(--theme-blue);
  font-size: 11px;
  cursor: pointer;
}

/* Route Plans Section */
.plans-title {
  font-size: 14px;
  font-weight: 700;
  color: #1d1d1f;
  margin: 8px 0 10px;
}

.plans-heading-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.result-count {
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 500;
}

.sort-select {
  max-width: 118px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: var(--radius-pill);
  outline: 0;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.7);
  font-size: 11px;
}

.request-error-box {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 28px 12px;
  border: 1px solid rgba(255, 59, 48, 0.16);
  border-radius: var(--radius-md);
  background: rgba(255, 245, 245, 0.8);
  text-align: center;
}

.request-error-box p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 8px 0 2px;
}

.route-plans-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.route-plan-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  cursor: pointer;
  transition: all 0.15s ease;
}

.route-plan-card:hover {
  background: rgba(255, 255, 255, 0.95);
  border-color: rgba(0, 0, 0, 0.12);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.plan-card-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.mode-icon {
  display: flex;
  align-items: center;
}

.plan-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.plan-info h5 {
  font-size: 13px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0 0 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.plan-specs {
  font-size: 11px;
  color: var(--text-secondary);
}

.plan-tag-text {
  font-size: 10px;
  color: var(--theme-blue);
  margin-top: 2px;
}

.plan-card-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.plan-price {
  font-size: 15px;
  font-weight: 700;
  color: var(--price-color);
}

@media (max-width: 420px) {
  .plans-heading-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .sort-select {
    max-width: none;
    width: 100%;
  }
}
</style>
