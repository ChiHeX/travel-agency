<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const currentRoute = useRoute()
const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})

const form = reactive({
  departureCity: currentRoute.query.departureCity || '',
  destination: currentRoute.query.destination || '',
  durationDays: currentRoute.query.durationDays || '',
  departureMonth: currentRoute.query.departureMonth || '',
  minPrice: currentRoute.query.minPrice || '',
  maxPrice: currentRoute.query.maxPrice || '',
  hasDeparture: currentRoute.query.hasDeparture === 'true',
  sortBy: currentRoute.query.sortBy || ''
})

const routes = ref([])
const total = ref(0)
const page = ref(Number(currentRoute.query.page) || 1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')
const showAdvancedFilters = ref(false)
const appliedSearchKey = ref('')
let latestRequest = 0

const hasEndpoints = computed(() => Boolean(form.departureCity.trim() && form.destination.trim()))
const showResults = computed(() =>
  hasEndpoints.value && appliedSearchKey.value === JSON.stringify(buildParams())
)

function buildParams() {
  const sortMap = {
    priceAsc: 'minAdultPrice,asc',
    priceDesc: 'minAdultPrice,desc',
    bookingCount: 'validBookingCount,desc',
    rating: 'ratingAvg,desc'
  }
  return {
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
      departureCity: form.departureCity,
      destination: form.destination,
      durationDays: form.durationDays,
      departureMonth: form.departureMonth,
      minPrice: form.minPrice,
      maxPrice: form.maxPrice,
      hasDeparture: form.hasDeparture ? 'true' : '',
      sortBy: form.sortBy,
      page: page.value > 1 ? String(page.value) : ''
    }).filter(([, value]) => value !== '' && value != null)
  )
  router.replace({ name: 'routes', query })
}

async function load(options = {}) {
  if (options.resetPage) page.value = 1
  const requestId = ++latestRequest
  const params = buildParams()
  errorMessage.value = ''
  syncQuery()
  if (!hasEndpoints.value) {
    routes.value = []
    total.value = 0
    appliedSearchKey.value = ''
    loading.value = false
    return
  }
  appliedSearchKey.value = JSON.stringify(params)
  loading.value = true
  try {
    const data = await routeApi.list(params)
    if (requestId !== latestRequest) return
    routes.value = data?.items || []
    total.value = data?.total || 0
    if (data?.page && data.page !== page.value) {
      page.value = data.page
      appliedSearchKey.value = JSON.stringify(buildParams())
    }
  } catch (error) {
    if (requestId !== latestRequest) return
    routes.value = []
    total.value = 0
    errorMessage.value = error.message || '线路加载失败，请稍后重试'
  } finally {
    if (requestId === latestRequest) loading.value = false
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
    departureCity: '',
    destination: '',
    durationDays: '',
    departureMonth: '',
    minPrice: '',
    maxPrice: '',
    hasDeparture: false,
    sortBy: ''
  })
  load({ resetPage: true })
}

function swapEndpoints() {
  const departureCity = form.departureCity
  form.departureCity = form.destination
  form.destination = departureCity
  load({ resetPage: true })
}

function applyFilters() {
  if (form.minPrice !== '' && form.maxPrice !== '' && Number(form.minPrice) > Number(form.maxPrice)) {
    latestRequest += 1
    routes.value = []
    total.value = 0
    loading.value = false
    appliedSearchKey.value = JSON.stringify(buildParams())
    errorMessage.value = '最低价格不能高于最高价格'
    return
  }
  load({ resetPage: true })
}

onMounted(load)
</script>

<template>
  <div class="route-drawer-panel">
    <div class="drawer-header-bar">
      <h2>路线</h2>
      <div class="header-action-icons">
        <button type="button" class="drawer-icon-btn" title="清空路线与筛选" aria-label="清空路线与筛选" @click="resetFilters">
          <AppIcon name="refresh" size="13" />
        </button>
        <button type="button" class="drawer-icon-btn" title="关闭面板" aria-label="关闭面板" @click="closeDrawer">
          <AppIcon name="close" size="13" />
        </button>
      </div>
    </div>

    <div class="drawer-scroll-body">
      <p class="route-intro">选择出发地与目的地，查看可报名的跟团游。</p>
      <div class="waypoints-card-box">
        <div class="waypoint-row">
          <span class="waypoint-bullet blue">
            <AppIcon name="circle" size="13" color="#0071e3" />
          </span>
          <div class="waypoint-input-box">
            <label class="input-sub" for="route-departure">出发城市</label>
            <input id="route-departure" v-model="form.departureCity" placeholder="从哪里出发" @change="load({ resetPage: true })" @keyup.enter="$event.target.blur()" />
          </div>
        </div>

        <div class="vertical-connector-line"></div>

        <div class="waypoint-row">
          <span class="waypoint-bullet blue">
            <AppIcon name="pin" size="13" color="#0071e3" />
          </span>
          <div class="waypoint-input-box">
            <label class="input-sub" for="route-destination">目的地</label>
            <input id="route-destination" v-model="form.destination" placeholder="想去哪里" @change="load({ resetPage: true })" @keyup.enter="$event.target.blur()" />
          </div>
        </div>
        <button type="button" class="swap-endpoints-btn" title="交换出发城市与目的地" aria-label="交换出发城市与目的地" @click="swapEndpoints">⇅</button>
      </div>

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
        <label class="availability-filter">
          <input v-model="form.hasDeparture" type="checkbox" @change="applyFilters" />
          <span>仅显示有可报名团期的线路</span>
        </label>
      </details>

      <div v-if="!showResults" class="route-search-hint">
        <AppIcon name="pin" size="18" color="#8e8e93" />
        <p>{{ hasEndpoints ? '确认地点或应用筛选后，显示更新的跟团游方案。' : '填写出发城市和目的地后，显示匹配的跟团游方案。' }}</p>
      </div>

      <div v-else class="route-plans-section">
        <div class="plans-heading-row">
          <h4 class="plans-title">可选线路 <span v-if="!loading" class="result-count">{{ total }} 条</span></h4>
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
          <button
            v-for="item in routes"
            :key="item.id"
            type="button"
            class="route-plan-card"
            :class="{ 'without-cover': !item.coverUrl }"
            @click="openRoute(item.id)"
          >
            <img v-if="item.coverUrl" class="plan-cover" :src="item.coverUrl" alt="" loading="lazy" />
            <div class="plan-info">
              <h5>{{ item.destination }}</h5>
              <p class="plan-route-name" :title="item.name">{{ item.name }}</p>
              <p class="plan-specs">{{ item.departureCity }}出发 · {{ item.durationDays }}日行程</p>
              <div class="plan-card-footer">
                <span v-if="item.nextDepartureDate" class="plan-departure-date">最近团期 {{ item.nextDepartureDate }}</span>
                <span class="plan-card-right">
                  <strong v-if="item.minAdultPrice != null" class="plan-price">¥{{ item.minAdultPrice }}</strong>
                  <span v-else class="plan-price pending-price">价格待发布</span>
                </span>
              </div>
            </div>
          </button>
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
  gap: 14px;
}

.route-intro {
  margin: 0 2px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.route-search-hint {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 24px 8px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.route-search-hint p { margin: 0; }

.waypoints-card-box {
  position: relative;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-md);
  padding: 14px 50px 14px 16px;
  display: flex;
  flex-direction: column;
}

.waypoint-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 42px;
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
  font-size: 11px;
  color: var(--text-tertiary);
}

.waypoint-input-box input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  font-weight: 500;
  color: #1d1d1f;
  padding: 2px 0;
}

.waypoint-input-box input::placeholder {
  color: #8e8e93;
  font-weight: 400;
}

.waypoint-input-box input:focus-visible {
  outline: 2px solid var(--theme-blue);
  outline-offset: 3px;
  border-radius: 2px;
}

.swap-endpoints-btn {
  position: absolute;
  top: 50%;
  right: 13px;
  width: 30px;
  height: 30px;
  transform: translateY(-50%);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 9px;
  background: #fff;
  color: var(--theme-blue);
  font-size: 18px;
  cursor: pointer;
}

.swap-endpoints-btn:hover {
  background: var(--theme-blue-tint);
}

.vertical-connector-line {
  width: 2px;
  height: 16px;
  background: rgba(0, 0, 0, 0.08);
  margin-left: 6px;
  margin-top: 3px;
  margin-bottom: 3px;
}

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

.availability-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.availability-filter input {
  margin: 0;
  accent-color: var(--theme-blue);
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
  display: grid;
  width: 100%;
  grid-template-columns: 108px minmax(0, 1fr);
  min-height: 136px;
  overflow: hidden;
  padding: 0;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
}

.route-plan-card.without-cover { grid-template-columns: minmax(0, 1fr); }

.route-plan-card:hover {
  background: rgba(255, 255, 255, 0.95);
  border-color: rgba(0, 0, 0, 0.12);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.plan-cover { width: 100%; height: 100%; min-height: 136px; object-fit: cover; }

.plan-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 11px 12px;
}

.plan-info h5 {
  font-size: 16px;
  font-weight: 650;
  color: #1d1d1f;
  margin: 0;
  overflow: hidden;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-route-name { display: -webkit-box; overflow: hidden; margin: 0; color: #3c3c43; font-size: 12px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }

.plan-specs {
  overflow: hidden;
  margin: 0;
  font-size: 11px;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-tag-text {
  font-size: 10px;
  color: var(--theme-blue);
  margin-top: 2px;
}

.plan-card-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin-left: auto;
}

.plan-card-footer { display: flex; align-items: flex-end; justify-content: space-between; gap: 6px; margin-top: auto; }
.plan-departure-date { overflow: hidden; color: var(--text-secondary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }

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
