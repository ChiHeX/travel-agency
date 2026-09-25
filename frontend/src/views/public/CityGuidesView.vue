<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { placeGuideApi } from '@/api/modules'
import AppIcon from '@/components/AppIcon.vue'
import PanelIconButton from '@/components/PanelIconButton.vue'
import RequestState from '@/components/RequestState.vue'

const route = useRoute()
const router = useRouter()
const closeDrawer = inject('closeDrawer', () => {})
const articles = ref([])
const cityData = ref([])
const loading = ref(true)
const error = ref('')
const failedImages = ref(new Set())
const showScopeMenu = ref(false)
const openRegion = ref('')
const wideSubmenu = ref(false)
const submenuPosition = ref({ top: '0px', left: '0px', width: '320px' })

const guideCardsRef = ref(null)
const latestCardsRef = ref(null)
const canScrollGuideLeft = ref(false)
const canScrollGuideRight = ref(false)
const canScrollLatestLeft = ref(false)
const canScrollLatestRight = ref(false)

const city = computed(() => String(route.params.city || ''))
const cityArticles = computed(() => articles.value.filter((item) => item.city === city.value))
const featured = computed(() => cityArticles.value[0] || null)
const guideCards = computed(() => cityArticles.value.slice(1, 7))
const latestCards = computed(() => cityArticles.value.slice(0, 6))
const otherCities = computed(() => {
  const groups = new Map()
  for (const item of cityData.value) {
    if (!item.city || item.city === city.value) continue
    groups.set(item.city, { city: item.city, count: item.count,
      coverArticle: item.coverUrl ? { id: item.city, coverUrl: item.coverUrl } : null })
  }
  return [...groups.values()]
})
const regionGroups = computed(() => {
  const groups = new Map()
  for (const item of cityData.value) {
    const region = item.destination || item.city
    if (!region) continue
    const group = groups.get(region) || { name: region, cities: new Set() }
    if (item.city) group.cities.add(item.city)
    groups.set(region, group)
  }
  return [...groups.values()].map((group) => ({ name: group.name, cities: [...group.cities] }))
})

function updateGuideScrollState() {
  if (!guideCardsRef.value) return
  const { scrollLeft, scrollWidth, clientWidth } = guideCardsRef.value
  canScrollGuideLeft.value = scrollLeft > 6
  canScrollGuideRight.value = scrollLeft + clientWidth < scrollWidth - 6
}

function updateLatestScrollState() {
  if (!latestCardsRef.value) return
  const { scrollLeft, scrollWidth, clientWidth } = latestCardsRef.value
  canScrollLatestLeft.value = scrollLeft > 6
  canScrollLatestRight.value = scrollLeft + clientWidth < scrollWidth - 6
}

function updateAllScrollStates() {
  updateGuideScrollState()
  updateLatestScrollState()
}

function scrollCarousel(el, direction) {
  if (!el) return
  const card = el.firstElementChild
  const step = card ? card.offsetWidth + 10 : el.clientWidth * 0.75
  el.scrollBy({
    left: direction === 'left' ? -step : step,
    behavior: 'smooth'
  })
}

watch([guideCards, latestCards, loading], async () => {
  await nextTick()
  updateAllScrollStates()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result, cities] = await Promise.all([
      placeGuideApi.list({ page: 1, size: 100, city: city.value }),
      placeGuideApi.cities()
    ])
    articles.value = result?.items || []
    cityData.value = cities || []
  } catch (cause) {
    error.value = cause.message || '城市指南加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  window.addEventListener('resize', updateAllScrollStates, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('resize', updateAllScrollStates)
})

function markImageFailed(id) {
  failedImages.value = new Set(failedImages.value).add(String(id))
}

function openArticle(id) {
  router.push({ name: 'guide-detail', params: { id } })
}

function openCity(name) {
  closeScopeMenu()
  if (name === city.value) return
  router.push({ name: 'city-guides', params: { city: name } })
}

function setSubmenuPosition(target) {
  const rect = target?.getBoundingClientRect()
  if (!rect) return
  const width = Math.min(360, Math.max(280, window.innerWidth * 0.3))
  submenuPosition.value = {
    top: `${Math.min(rect.top, window.innerHeight - 260)}px`,
    left: `${Math.min(rect.right + 12, window.innerWidth - width - 18)}px`,
    width: `${width}px`
  }
}

function selectGlobal() {
  closeScopeMenu()
  router.push({ name: 'guides' })
}

function selectRegion(group, event) {
  if (!group.cities.length) return
  if (openRegion.value === group.name && wideSubmenu.value) {
    openRegion.value = ''
    wideSubmenu.value = false
    return
  }
  openRegion.value = group.name
  wideSubmenu.value = window.innerWidth > 900
  if (wideSubmenu.value) setSubmenuPosition(event.currentTarget)
}

function closeScopeMenu() {
  showScopeMenu.value = false
  openRegion.value = ''
  wideSubmenu.value = false
}

function toggleScopeMenu() {
  showScopeMenu.value = !showScopeMenu.value
  if (!showScopeMenu.value) {
    closeScopeMenu()
    return
  }
  openRegion.value = ''
  wideSubmenu.value = false
}

watch(city, load, { immediate: true })
</script>

<template>
  <div class="city-guide-page">
    <header class="city-header">
      <div>
        <h2>指南</h2>
        <button type="button" class="city-selector" :aria-expanded="showScopeMenu" aria-haspopup="menu" aria-label="切换指南城市" @click="toggleScopeMenu">
          <span>{{ city }}</span><AppIcon name="chevron-down" size="14" />
        </button>
      </div>
      <PanelIconButton action="close" label="关闭指南" @click="closeDrawer" />
    </header>

    <div v-if="showScopeMenu" class="scope-menu-layer" @click.self="closeScopeMenu">
      <div class="scope-menu" role="menu" aria-label="指南浏览范围">
        <template v-if="!openRegion || wideSubmenu">
          <button type="button" role="menuitem" @click="selectGlobal"><span>全球</span></button>
          <button v-for="group in regionGroups" :key="group.name" type="button" role="menuitem" :class="{ selected: openRegion === group.name }" @click="selectRegion(group, $event)">
            <span>{{ group.name }}</span><AppIcon v-if="group.cities.length" name="chevron-right" size="20" />
          </button>
        </template>
        <template v-else>
          <button type="button" class="menu-back" role="menuitem" @click="openRegion = ''"><AppIcon name="chevron-left" size="18" /><strong>{{ openRegion }}</strong></button>
          <button v-for="name in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="name" type="button" role="menuitem" :class="{ selected: name === city }" @click="openCity(name)">
            <span>{{ name }}</span><span v-if="name === city" class="checkmark" aria-label="当前城市">✓</span>
          </button>
        </template>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showScopeMenu && openRegion && wideSubmenu" class="city-scope-submenu" :style="submenuPosition" role="menu" :aria-label="`${openRegion}城市`">
        <button v-for="name in regionGroups.find((group) => group.name === openRegion)?.cities || []" :key="name" type="button" role="menuitem" :class="{ selected: name === city }" @click="openCity(name)">
          <span><strong>{{ name }}</strong><small>{{ openRegion }}</small></span><span v-if="name === city" class="checkmark" aria-label="当前城市">✓</span>
        </button>
      </div>
    </Teleport>

    <main>
      <RequestState v-if="error" :error="error" @retry="load" />
      <div v-else-if="loading" class="loading-block"><el-skeleton :rows="12" animated /></div>
      <template v-else-if="cityArticles.length">
        <button v-if="featured" type="button" class="hero-card" @click="openArticle(featured.id)">
          <div class="media-fallback"><AppIcon name="guides" size="34" /></div>
          <img v-if="featured.coverUrl && !failedImages.has(String(featured.id))" :src="featured.coverUrl" :alt="featured.title" @error="markImageFailed(featured.id)" />
          <i></i>
          <span><small>{{ featured.authorName }}</small><strong>{{ featured.title }}</strong></span>
        </button>

        <section v-if="guideCards.length" class="guide-section">
          <h2>旅行指南</h2>
          <div class="carousel-container">
            <button
              v-show="canScrollGuideLeft"
              type="button"
              class="carousel-nav-btn prev"
              aria-label="向左滚动"
              @click="scrollCarousel(guideCardsRef, 'left')"
            >
              <AppIcon name="chevron-left" size="16" />
            </button>
            <div ref="guideCardsRef" class="card-row" @scroll.passive="updateGuideScrollState">
              <button v-for="article in guideCards" :key="article.id" type="button" class="guide-card" @click="openArticle(article.id)">
                <div class="media-fallback"><AppIcon name="guides" size="28" /></div>
                <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
                <i></i><span><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
              </button>
            </div>
            <button
              v-show="canScrollGuideRight"
              type="button"
              class="carousel-nav-btn next"
              aria-label="向右滚动"
              @click="scrollCarousel(guideCardsRef, 'right')"
            >
              <AppIcon name="chevron-right" size="16" />
            </button>
          </div>
        </section>

        <section class="guide-section">
          <RouterLink class="section-title" :to="{ name: 'latest-guides', query: { city } }">
            <h2>最新</h2><AppIcon name="chevron-right" size="16" color="#8e8e93" />
          </RouterLink>
          <div class="carousel-container">
            <button
              v-show="canScrollLatestLeft"
              type="button"
              class="carousel-nav-btn prev"
              aria-label="向左滚动"
              @click="scrollCarousel(latestCardsRef, 'left')"
            >
              <AppIcon name="chevron-left" size="16" />
            </button>
            <div ref="latestCardsRef" class="card-row" @scroll.passive="updateLatestScrollState">
              <button v-for="article in latestCards" :key="article.id" type="button" class="guide-card" @click="openArticle(article.id)">
                <div class="media-fallback"><AppIcon name="guides" size="28" /></div>
                <img v-if="article.coverUrl && !failedImages.has(String(article.id))" :src="article.coverUrl" :alt="article.title" @error="markImageFailed(article.id)" />
                <i></i><span><small>{{ article.authorName }}</small><strong>{{ article.title }}</strong></span>
              </button>
            </div>
            <button
              v-show="canScrollLatestRight"
              type="button"
              class="carousel-nav-btn next"
              aria-label="向右滚动"
              @click="scrollCarousel(latestCardsRef, 'right')"
            >
              <AppIcon name="chevron-right" size="16" />
            </button>
          </div>
        </section>

        <section v-if="otherCities.length" class="guide-section cities-section">
          <h2>城市</h2>
          <div class="city-grid">
            <button v-for="item in otherCities" :key="item.city" type="button" @click="openCity(item.city)">
              <img v-if="item.coverArticle && !failedImages.has(String(item.coverArticle.id))" :src="item.coverArticle.coverUrl" :alt="item.city" @error="markImageFailed(item.coverArticle.id)" />
              <i></i><span>{{ item.city }}</span><small>{{ item.count }} 个指南</small>
            </button>
          </div>
        </section>
      </template>
      <RequestState v-else empty :empty-text="`${city}暂无已发布指南`" />
    </main>
  </div>
</template>

<style scoped>
.city-guide-page { position: relative; height: 100%; overflow-y: auto; color: #111; background: #f7faf9; }
.city-header { position: sticky; z-index: 5; top: 0; display: flex; align-items: flex-start; justify-content: space-between; min-height: 60px; padding: 16px 20px 10px; background: rgba(247,250,249,.9); backdrop-filter: blur(18px) saturate(150%); }
.city-header h1, .city-header h2 { margin: 0; color: #1d1d1f; font-size: 20px; font-weight: 700; line-height: 1.2; letter-spacing: -0.01em; }
.city-selector { display: inline-flex; align-items: center; gap: 3px; margin: 3px 0 0 -3px; padding: 2px 4px; border: 0; border-radius: 6px; color: var(--theme-blue); background: transparent; font-size: 15px; font-weight: 650; line-height: 1.2; cursor: pointer; }
.city-selector svg { margin-top: 1px; transition: transform .18s ease; }.city-selector[aria-expanded="true"] svg { transform: rotate(180deg); }.city-selector:hover { background: rgba(0,113,227,.07); }
.scope-menu-layer { position: absolute; z-index: 4; inset: 64px 0 0; padding: 10px 18px; background: rgba(245,249,248,.18); }
.scope-menu { max-height: min(410px, calc(100% - 18px)); overflow-y: auto; border: 1px solid rgba(0,0,0,.05); border-radius: 16px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }
.scope-menu button { display: flex; align-items: center; justify-content: space-between; width: 100%; min-height: 52px; padding: 0 20px; border: 0; color: #1d1d1f; background: transparent; font-size: 15px; text-align: left; cursor: pointer; }.scope-menu button:hover, .scope-menu button.selected { background: #f2f2f7; }.scope-menu .menu-back { justify-content: flex-start; gap: 8px; border-bottom: 1px solid #e5e5e7; color: var(--theme-blue); }.scope-menu .menu-back strong { color: #1d1d1f; }.checkmark { margin-left: auto; font-size: 20px; font-weight: 400; }
:global(.city-scope-submenu) { position: fixed; z-index: 100; max-height: 360px; overflow-y: auto; padding: 8px; border: 1px solid rgba(0,0,0,.05); border-radius: 16px; background: rgba(255,255,255,.97); box-shadow: 0 10px 32px rgba(0,0,0,.2); backdrop-filter: blur(24px) saturate(150%); }
:global(.city-scope-submenu button) { display: flex; align-items: center; justify-content: space-between; width: 100%; min-height: 58px; padding: 10px 16px; border: 0; border-radius: 12px; background: transparent; text-align: left; cursor: pointer; }
:global(.city-scope-submenu button:hover), :global(.city-scope-submenu button.selected) { background: #f2f2f7; }:global(.city-scope-submenu button > span:first-child) { display: grid; }:global(.city-scope-submenu strong) { color: #1d1d1f; font-size: 15px; font-weight: 500; }:global(.city-scope-submenu small) { color: #737378; font-size: 13px; }:global(.city-scope-submenu .checkmark) { color: #1d1d1f; font-size: 20px; }
main { padding: 8px 22px 34px; }.loading-block { padding-top: 8px; }
.hero-card, .guide-card { position: relative; overflow: hidden; border: 0; color: white; background: #cbe7ec; text-align: left; cursor: pointer; }
.hero-card { width: 100%; height: 205px; border-radius: 18px; }
.hero-card img, .guide-card img, .media-fallback { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.media-fallback { display: grid; place-items: center; color: #2976a8; background: linear-gradient(145deg,#bde7f1,#dce9d0); }
.hero-card > i, .guide-card > i { position: absolute; inset: 0; background: linear-gradient(0deg,rgba(0,0,0,.72),transparent 64%); }
.hero-card > span, .guide-card > span { position: absolute; right: 16px; bottom: 15px; left: 16px; display: grid; gap: 3px; }
.hero-card small, .guide-card small { font-size: 11px; font-weight: 650; }.hero-card strong { font-size: 18px; line-height: 1.15; }.guide-card strong { font-size: 14px; line-height: 1.2; }
.guide-section { margin-top: 24px; }.guide-section h2 { margin: 0 0 10px; color: #1d1d1f; font-size: 15px; font-weight: 700; letter-spacing: -.01em; }
.section-title { display: inline-flex; align-items: center; gap: 3px; margin-bottom: 10px; border-radius: 7px; color: inherit; text-decoration: none; }.section-title h2 { margin: 0; color: #1d1d1f; font-size: 15px; font-weight: 700; }.section-title:hover { color: var(--theme-blue); }
.carousel-container { position: relative; }
.carousel-nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 10;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #1d1d1f;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.14);
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease, transform 0.18s ease, background 0.15s ease;
}
.carousel-container:hover .carousel-nav-btn {
  opacity: 1;
  pointer-events: auto;
}
.carousel-nav-btn:hover {
  background: #ffffff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.22);
  transform: translateY(-50%) scale(1.08);
}
.carousel-nav-btn:active {
  transform: translateY(-50%) scale(0.94);
}
.carousel-nav-btn.prev {
  left: -12px;
}
.carousel-nav-btn.next {
  right: -12px;
}
.card-row {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: calc(50% - 5px);
  gap: 10px;
  overflow-x: auto;
  scroll-behavior: smooth;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
}
.card-row::-webkit-scrollbar { display: none; }
.guide-card { height: 220px; border-radius: 17px; scroll-snap-align: start; }
.city-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.city-grid button { position: relative; min-height: 112px; padding: 14px; overflow: hidden; border: 0; border-radius: 16px; display: grid; align-content: end; justify-items: start; color: white; background: linear-gradient(145deg,#5f9fbd,#315f77); text-align: left; cursor: pointer; }
.city-grid button img, .city-grid button i { position: absolute; inset: 0; width: 100%; height: 100%; }.city-grid button img { object-fit: cover; }.city-grid button i { background: linear-gradient(0deg,rgba(0,0,0,.62),transparent 70%); }
.city-grid span, .city-grid small { position: relative; }.city-grid span { font-size: 15px; font-weight: 700; }.city-grid small { font-size: 11px; opacity: .85; }
@media (max-width: 900px) { .city-header { padding-top: 16px; }.hero-card { height: 225px; }.guide-card { height: 238px; }main { padding-bottom: calc(34px + env(safe-area-inset-bottom)); } }
</style>
