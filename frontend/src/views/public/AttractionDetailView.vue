<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { contentApi, routeApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import RequestState from '@/components/RequestState.vue'
import RouteResultCard from '@/components/RouteResultCard.vue'

const route = useRoute()
const router = useRouter()
const setMapFocus = inject('setMapFocus', () => {})
const place = ref(null)
const departures = ref([])
const routeCards = ref([])
const routeCardsLoading = ref(false)
const routeCardsError = ref('')
const relatedArticles = ref([])
const nearbyPlaces = ref([])
const loading = ref(true)
const error = ref('')
const failedImages = ref(new Set())

const hasCoordinates = computed(() => place.value?.longitude != null && place.value?.latitude != null)

async function loadRouteCards() {
  const routeIds = [...new Set(departures.value.map((departure) => departure.routeId))]
  routeCardsLoading.value = true
  routeCardsError.value = ''
  try {
    routeCards.value = await Promise.all(routeIds.map(async (id) => (await routeApi.detail(id)).route))
  } catch (cause) {
    routeCards.value = []
    routeCardsError.value = cause.message || '关联线路加载失败'
  } finally {
    routeCardsLoading.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  place.value = null
  departures.value = []
  routeCards.value = []
  routeCardsError.value = ''
  relatedArticles.value = []
  setMapFocus(null)
  try {
    const detail = await contentApi.attraction(route.params.id)
    place.value = detail.attraction
    departures.value = detail.departures || []
    if (departures.value.length) loadRouteCards()
    if (hasCoordinates.value) {
      setMapFocus({ latitude: place.value.latitude, longitude: place.value.longitude })
    }
    try {
      const nearby = await contentApi.attractions({ page: 1, size: 20, city: place.value.city })
      nearbyPlaces.value = (nearby?.items || []).filter((item) => String(item.id) !== String(place.value.id)).slice(0, 6)
    } catch (_) {
      nearbyPlaces.value = []
    }
    try {
      const articleResult = await contentApi.articles({ page: 1, size: 100, destination: place.value.city })
      relatedArticles.value = (articleResult?.items || []).filter((item) => String(item.attractionId) === String(place.value.id))
    } catch (_) {
      relatedArticles.value = []
    }
  } catch (cause) {
    error.value = cause.message || '地点详情加载失败'
  } finally {
    loading.value = false
  }
}

async function share() {
  const data = { title: place.value?.name || '旅行地点', url: window.location.href }
  if (navigator.share) await navigator.share(data)
  else await navigator.clipboard?.writeText(data.url)
}

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

watch(() => route.params.id, load, { immediate: true })
onBeforeUnmount(() => setMapFocus(null))
</script>

<template>
  <div class="place-detail">
    <RequestState v-if="error" :error="error" @retry="load" />
    <div v-else-if="loading" class="loading-block"><el-skeleton :rows="12" animated /></div>
    <template v-else-if="place">
      <header class="place-header">
        <div class="top-actions"><button type="button" class="circle-button" aria-label="返回" @click="router.back()"><AppIcon name="chevron-left" size="19" /></button><button type="button" class="circle-button" aria-label="分享地点" @click="share"><AppIcon name="share" size="18" /></button></div>
        <div class="place-kicker"><AppIcon name="pin" size="14" /><span>{{ place.city }} · 地点</span></div>
        <h1>{{ place.name }}</h1>
        <p v-if="place.address" class="place-address">{{ place.address }}</p>
      </header>

      <main>
        <section>
          <h2>关于</h2>
          <div class="info-card about-card"><p v-if="place.intro">{{ place.intro }}</p><p v-else class="muted">该地点暂无公开简介。</p>
          </div>
        </section>

        <section id="place-departures">
          <h2>途经此地点的线路</h2>
          <p v-if="!departures.length" class="departure-empty">暂无有未来开放团期的途经线路</p>
          <div v-else-if="routeCardsError" class="departure-empty">
            <p>{{ routeCardsError }}</p>
            <button type="button" class="retry-button" @click="loadRouteCards">重新加载</button>
          </div>
          <div v-else-if="routeCardsLoading" class="departure-list"><el-skeleton :rows="3" animated /></div>
          <div v-else class="departure-list">
            <RouteResultCard v-for="item in routeCards" :key="item.id" :route="item" />
          </div>
        </section>

        <section>
          <h2>旅行攻略</h2>
          <div v-if="relatedArticles.length" class="horizontal-list">
            <RouterLink v-for="article in relatedArticles" :key="article.id" :to="{ name: 'article-detail', params: { id: article.id } }" class="guide-tile">
              <div class="tile-fallback"><AppIcon name="guides" size="25" /></div>
              <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
              <strong>{{ article.title }}</strong><small>{{ article.authorName }}</small>
            </RouterLink>
          </div>
          <RouterLink :to="{ name: 'articles', query: { destination: place.city } }" class="more-articles">查看{{ place.city }}的攻略 <AppIcon name="chevron-right" size="16" /></RouterLink>
        </section>

        <section v-if="nearbyPlaces.length">
          <h2>同城其他地点</h2>
          <div class="horizontal-list nearby-list">
            <RouterLink v-for="nearby in nearbyPlaces" :key="nearby.id" :to="{ name: 'attraction-detail', params: { id: nearby.id }, query: { city: nearby.city } }" class="nearby-tile">
              <span><AppIcon name="pin" size="18" /></span><strong>{{ nearby.name }}</strong><small>{{ nearby.city }}</small>
            </RouterLink>
          </div>
        </section>

        <section>
          <h2>详细信息</h2>
          <div class="info-card details-card">
            <div v-if="place.address"><small>地址</small><p>{{ place.address }}</p></div>
            <div><small>城市</small><p>{{ place.city }}</p></div>
            <div v-if="hasCoordinates"><small>坐标</small><p>{{ place.latitude }}, {{ place.longitude }}</p></div>
            <div v-if="place.dataSource"><small>资料来源</small><p>{{ place.dataSource }}</p></div>
          </div>
        </section>
      </main>
    </template>
  </div>
</template>

<style scoped>
.place-detail { height: 100%; overflow-y: auto; color: #101010; background: #f4f8fa; }.loading-block { padding: 28px 22px; }
.place-header { padding: 20px 22px 24px; background: #fff; border-bottom: 1px solid rgba(0,0,0,.07); }.top-actions { display: flex; justify-content: space-between; margin-bottom: 28px; }
.circle-button { width: 38px; height: 38px; border: 0; border-radius: 50%; display: grid; place-items: center; color: #49535b; background: #f0f2f4; cursor: pointer; }.circle-button:hover, .circle-button:focus-visible { background: #e3e8ed; }
.place-kicker { display: flex; align-items: center; gap: 5px; color: var(--theme-blue); font-size: 12px; font-weight: 700; }
.place-header h1 { margin: 7px 0 0; font-size: clamp(28px, 6vw, 34px); line-height: 1.15; letter-spacing: -.04em; }.place-address { margin: 8px 0 0; color: #66717b; font-size: 14px; line-height: 1.5; }
main { display: grid; gap: 28px; padding: 24px 22px 36px; }section h2 { margin: 0 0 10px; font-size: 22px; letter-spacing: -.035em; }
.info-card { overflow: hidden; border-radius: 18px; background: white; }.about-card { padding: 20px; }.about-card p { margin: 0; font-size: 16px; line-height: 1.65; white-space: pre-wrap; }
.departure-list { display: grid; gap: 10px; }.departure-empty { color: var(--text-secondary); }.departure-empty p { margin: 0 0 8px; }.retry-button { padding: 6px 10px; border: 0; border-radius: 8px; color: white; background: var(--theme-blue); cursor: pointer; }
.about-card .muted { color: #8e8e93; }
.horizontal-list { display: grid; grid-auto-flow: column; grid-auto-columns: 72%; gap: 10px; overflow-x: auto; scrollbar-width: none; }.horizontal-list::-webkit-scrollbar { display: none; }
.guide-tile { position: relative; overflow: hidden; border-radius: 17px; background: white; }.guide-tile img, .tile-fallback { width: 100%; height: 130px; object-fit: cover; }.guide-tile img { position: absolute; inset: 0 0 auto; }.tile-fallback { display: grid; place-items: center; color: #3184aa; background: #bfe7ef; }.guide-tile strong, .guide-tile small { position: relative; display: block; margin: 11px 13px 0; }.guide-tile strong { font-size: 15px; line-height: 1.2; }.guide-tile small { margin-top: 3px; margin-bottom: 12px; color: #8e8e93; }
.more-articles { display: inline-flex; align-items: center; gap: 3px; margin-top: 12px; color: var(--theme-blue); font-size: 13px; font-weight: 700; }
.nearby-list { grid-auto-columns: 60%; }.nearby-tile { min-height: 130px; padding: 17px; border-radius: 17px; display: grid; align-content: start; background: white; }.nearby-tile > span { width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; color: white; background: #36c86c; }.nearby-tile strong { margin-top: 14px; font-size: 16px; line-height: 1.15; }.nearby-tile small { margin-top: 4px; color: #8e8e93; }
.details-card > div { padding: 15px 20px; border-bottom: 1px solid #e5e5e8; }.details-card > div:last-child { border-bottom: 0; }.details-card small { color: #8e8e93; }.details-card p { margin: 2px 0 0; overflow-wrap: anywhere; font-size: 15px; }
</style>
