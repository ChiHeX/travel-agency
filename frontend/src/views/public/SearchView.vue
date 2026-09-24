<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { homeApi, routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'

const router = useRouter()
const currentRoute = useRoute()
const closeDrawer = inject('closeDrawer', () => {})

const keyword = ref(currentRoute.query.keyword || '')
const submittedKeyword = ref(String(currentRoute.query.keyword || '').trim())
const routes = ref([])
const total = ref(0)
const page = ref(Number(currentRoute.query.page) || 1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')
const home = ref(null)
const homeLoading = ref(false)
const homeError = ref('')
const selectedCollection = ref(null)
const collections = [
  { key: 'recommendedRoutes', title: '推荐线路' },
  { key: 'upcomingRoutes', title: '近期可报名' }
]

const isDiscovery = computed(() => !submittedKeyword.value)
const visibleRoutes = computed(() => isDiscovery.value && selectedCollection.value
  ? home.value?.[selectedCollection.value] || [] : routes.value)
const visibleLoading = computed(() => isDiscovery.value && selectedCollection.value ? homeLoading.value : loading.value)
const visibleError = computed(() => isDiscovery.value && selectedCollection.value ? homeError.value : errorMessage.value)
const resultsTitle = computed(() => {
  if (!isDiscovery.value) return `搜索结果 (${total.value})`
  return collections.find((item) => item.key === selectedCollection.value)?.title || `全部线路 (${total.value})`
})

async function loadRoutes() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await routeApi.list({
      keyword: submittedKeyword.value || undefined,
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

async function loadDiscovery() {
  homeLoading.value = true
  homeError.value = ''
  try {
    home.value = await homeApi.get()
  } catch (error) {
    home.value = null
    homeError.value = error.message || '推荐内容加载失败，请稍后重试'
  } finally {
    homeLoading.value = false
  }
}

function search(value = keyword.value) {
  keyword.value = value
  submittedKeyword.value = String(value).trim()
  selectedCollection.value = null
  page.value = 1
  router.replace({ name: 'search', query: submittedKeyword.value ? { keyword: submittedKeyword.value } : {} })
  if (isDiscovery.value) loadDiscovery()
  loadRoutes()
}

function changePage(nextPage) {
  page.value = nextPage
  router.replace({ name: 'search', query: {
    ...(isDiscovery.value ? {} : { keyword: submittedKeyword.value }),
    page: String(page.value)
  } })
  loadRoutes()
}

function toggleCollection(key) {
  selectedCollection.value = selectedCollection.value === key ? null : key
}

function openRoute(id) {
  router.push({ name: 'route-detail', params: { id } })
}

function openDestination(destination) {
  search(destination)
}

onMounted(() => {
  if (isDiscovery.value) loadDiscovery()
  loadRoutes()
})
</script>

<template>
  <div class="search-drawer-panel">
    <div class="drawer-header-bar">
      <h2>搜索</h2>
      <button type="button" class="drawer-close-btn" title="关闭面板" @click="closeDrawer">
        <AppIcon name="close" size="13" color="#86868b" />
      </button>
    </div>

    <div class="drawer-search-box">
      <form class="search-input-pill" @submit.prevent="search()">
        <AppIcon name="search" size="15" color="#8e8e93" />
        <input v-model="keyword" placeholder="搜索目的地、线路或景点" aria-label="搜索目的地、线路或景点" autofocus />
        <button v-if="keyword" type="button" class="clear-btn" title="清空搜索" @click="search('')">
          <AppIcon name="close" size="10" color="#ffffff" />
        </button>
      </form>
    </div>

    <main class="drawer-scroll-body">
      <section v-if="isDiscovery && home?.popularDestinations?.length" class="discovery-section">
        <div class="results-title-row"><h3 class="section-title">热门目的地</h3></div>
        <div class="destination-pills-row">
          <button v-for="item in home.popularDestinations" :key="item.destination" type="button" class="destination-pill" @click="openDestination(item.destination)">
            <AppIcon name="pin" size="13" color="#0071e3" />
            <span>{{ item.destination }}</span>
          </button>
        </div>
      </section>

      <section class="results-section">
        <div v-if="isDiscovery" class="collection-pills-row" role="group" aria-label="线路分类">
          <button v-for="collection in collections" :key="collection.key" type="button" class="collection-pill"
            :class="{ active: selectedCollection === collection.key }"
            :aria-pressed="selectedCollection === collection.key" @click="toggleCollection(collection.key)">
            {{ collection.title }}
          </button>
        </div>
        <div class="results-title-row">
          <h3 class="section-title">{{ resultsTitle }}</h3>
        </div>

        <div v-if="visibleLoading" class="skeleton-list"><el-skeleton v-for="i in 3" :key="i" :rows="3" animated style="margin-bottom: 12px" /></div>
        <div v-else-if="visibleError" class="empty-results error-results">
          <strong>线路暂时不可用</strong><p>{{ visibleError }}</p>
          <button type="button" class="secondary-button" @click="selectedCollection ? loadDiscovery() : loadRoutes()">重新加载</button>
        </div>
        <div v-else-if="visibleRoutes.length" class="route-cards-feed">
          <button v-for="item in visibleRoutes" :key="item.id" type="button" class="place-card-item" @click="openRoute(item.id)">
            <div class="place-thumb">
              <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.name" loading="lazy" />
              <div v-else class="place-thumb-fallback"><span>{{ item.destination?.slice(0, 2) || '—' }}</span></div>
              <span class="duration-pill">{{ item.durationDays }} 日游</span>
            </div>
            <div class="place-info">
              <h4 :title="item.name">{{ item.name }}</h4>
              <div class="place-route-meta"><span>{{ item.departureCity }} 出发</span><span>·</span><span>目的地 {{ item.destination }}</span></div>
              <div class="place-bottom-row">
                <span v-if="item.ratingCount" class="rating-badge"><AppIcon name="star" size="11" color="#ff9500" /><span>{{ item.ratingAvg }}</span></span>
                <span v-else class="rating-badge muted-rating">暂无评分</span>
                <div class="price-figure"><template v-if="item.minAdultPrice != null"><strong>¥{{ item.minAdultPrice }}</strong><small>起/人</small></template><small v-else>价格待发布</small></div>
              </div>
            </div>
          </button>
        </div>
        <div v-else class="empty-results"><p>{{ selectedCollection ? '暂无该分类的线路。' : isDiscovery ? '暂无已发布线路。' : '未找到完全匹配的线路，请尝试其他关键词。' }}</p><button v-if="!isDiscovery" type="button" class="secondary-button" @click="search('')">查看全部线路</button></div>
        <div v-if="!selectedCollection && !visibleLoading && !visibleError && total > pageSize" class="pagination-wrap">
          <el-pagination :current-page="page" :page-size="pageSize" :total="total" background layout="prev, pager, next" @current-change="changePage" />
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.search-drawer-panel { display: flex; flex-direction: column; height: 100%; background: transparent; }
.drawer-header-bar { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px 10px; }
.drawer-header-bar h2 { margin: 0; color: #1d1d1f; font-size: 20px; font-weight: 700; letter-spacing: -.01em; }
.drawer-close-btn { display: grid; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 50%; background: rgba(0,0,0,.05); cursor: pointer; }
.drawer-search-box { padding: 4px 18px 14px; }
.search-input-pill { display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 12px; border-radius: var(--radius-sm); background: rgba(0,0,0,.05); }
.search-input-pill input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text-primary); font-size: 13px; }
.search-input-pill input::placeholder { color: #8e8e93; }
.clear-btn { display: grid; width: 16px; height: 16px; place-items: center; border: 0; border-radius: 50%; background: #c7c7cc; cursor: pointer; }
.drawer-scroll-body { display: flex; flex: 1; flex-direction: column; gap: 20px; overflow-y: auto; padding: 0 18px 24px; }
.discovery-section { display: flex; flex-direction: column; gap: 2px; }
.section-title { margin: 0 0 10px; color: #1d1d1f; font-size: 14px; font-weight: 700; }
.results-title-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.destination-pills-row { display: flex; flex-wrap: wrap; gap: 8px; }
.destination-pill { display: inline-flex; align-items: center; gap: 5px; padding: 7px 11px; border: 1px solid rgba(0,0,0,.07); border-radius: var(--radius-pill); background: rgba(255,255,255,.78); color: var(--text-primary); font-size: 12px; cursor: pointer; }
.destination-pill:hover { border-color: var(--theme-blue); background: #fff; }
.collection-pills-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
.collection-pill { padding: 7px 12px; border: 1px solid rgba(0,0,0,.1); border-radius: var(--radius-pill); background: rgba(255,255,255,.78); color: var(--text-primary); font: inherit; font-size: 12px; cursor: pointer; }
.collection-pill:hover, .collection-pill:focus-visible { border-color: var(--theme-blue); }
.collection-pill.active { border-color: var(--theme-blue); background: var(--theme-blue); color: #fff; }
.route-cards-feed { display: flex; flex-direction: column; gap: 10px; }
.place-card-item { display: grid; width: 100%; grid-template-columns: 88px 1fr; gap: 12px; padding: 10px; border: 1px solid rgba(0,0,0,.05); border-radius: 12px; background: rgba(255,255,255,.85); box-shadow: 0 1px 3px rgba(0,0,0,.03); color: inherit; font: inherit; text-align: left; cursor: pointer; transition: all .15s ease; }
.place-card-item:hover { border-color: rgba(0,0,0,.1); background: #fff; box-shadow: 0 4px 12px rgba(0,0,0,.06); transform: translateY(-1px); }
.place-thumb { position: relative; width: 88px; height: 72px; overflow: hidden; border-radius: 8px; background: #e5e5ea; }
.place-thumb img { width: 100%; height: 100%; object-fit: cover; }
.place-thumb-fallback { display: grid; width: 100%; height: 100%; place-items: center; background: var(--theme-blue); color: #fff; font-size: 14px; font-weight: 700; }
.duration-pill { position: absolute; top: 4px; left: 4px; padding: 1px 5px; border-radius: 3px; background: rgba(0,0,0,.65); color: #fff; font-size: 9px; font-weight: 600; }
.place-info { display: flex; flex-direction: column; justify-content: space-between; min-width: 0; }
.place-info h4 { overflow: hidden; margin: 0; color: #1d1d1f; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.place-route-meta { display: flex; gap: 4px; color: var(--text-secondary); font-size: 11px; }
.place-bottom-row { display: flex; align-items: center; justify-content: space-between; }
.rating-badge { display: inline-flex; align-items: center; gap: 3px; color: var(--status-orange); font-size: 11px; font-weight: 600; }
.muted-rating { color: var(--text-tertiary); font-weight: 500; }
.price-figure { display: flex; align-items: baseline; color: var(--price-color); }
.price-figure strong { font-size: 14px; font-weight: 700; }
.price-figure small { margin-left: 2px; color: var(--text-secondary); font-size: 10px; }
.empty-results { padding: 30px 10px; color: var(--text-secondary); font-size: 13px; text-align: center; }
.empty-results p { margin: 0 0 12px; }.error-results strong { color: var(--text-primary); font-size: 13px; }
.pagination-wrap { display: flex; justify-content: center; padding: 8px 0 2px; }
</style>
